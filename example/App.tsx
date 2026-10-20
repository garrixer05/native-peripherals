import React, { useEffect, useState } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  ScrollView,
  Platform,
  PermissionsAndroid,
} from 'react-native';
import { Manager, Service, Characteristic } from 'native-peripherals';

const SERVICE_UUID = '0000180F-0000-1000-8000-00805F9B34FB'; // Battery Service standard UUID
const CHAR_UUID = '00002A19-0000-1000-8000-00805F9B34FB';    // Battery Level

export default function App() {
  const [manager] = useState(() => new Manager());
  const [btState, setBtState] = useState<string>('unknown');
  const [isAdvertising, setIsAdvertising] = useState<boolean>(false);
  const [logs, setLogs] = useState<string[]>([]);

  const addLog = (msg: string) => {
    const time = new Date().toLocaleTimeString();
    setLogs(prev => [`[${time}] ${msg}`, ...prev.slice(0, 40)]);
  };

  useEffect(() => {
    const sub = manager.onStateChanged(state => {
      setBtState(state);
      addLog(`Bluetooth state changed: ${state}`);
    });

    const connSub = manager.onDeviceConnected(deviceId => {
      addLog(`Device connected: ${deviceId}`);
    });

    const disconnSub = manager.onDeviceDisconnected(deviceId => {
      addLog(`Device disconnected: ${deviceId}`);
    });

    return () => {
      sub.remove();
      connSub.remove();
      disconnSub.remove();
    };
  }, [manager]);

  const requestPermissions = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
        ]);
        const allGranted = Object.values(granted).every(
          res => res === PermissionsAndroid.RESULTS.GRANTED
        );
        addLog(`Android permissions: ${allGranted ? 'Granted' : 'Denied'}`);
        return allGranted;
      } catch (err: any) {
        addLog(`Permission error: ${err.message}`);
        return false;
      }
    }
    return true;
  };

  const startAdvertising = async () => {
    try {
      const ok = await requestPermissions();
      if (!ok) return;

      addLog('Starting Manager...');
      await manager.start();

      const batteryChar = new Characteristic({
        uuid: CHAR_UUID,
        properties: ['read', 'notify'],
        permissions: ['readable'],
        value: 'MTAw', // Base64 for "100"
      });

      const batteryService = new Service({
        uuid: SERVICE_UUID,
        primary: true,
        characteristics: [batteryChar],
      });

      addLog('Adding Battery Service...');
      await manager.addService(batteryService);

      addLog('Starting Advertising...');
      await manager.startAdvertising({
        name: 'BLE-Peripheral-Test',
        serviceUuids: [SERVICE_UUID],
      });

      setIsAdvertising(true);
      addLog('Advertising started successfully!');
    } catch (err: any) {
      addLog(`Error starting: ${err.message}`);
    }
  };

  const stopAdvertising = async () => {
    try {
      addLog('Stopping advertising...');
      await manager.stopAdvertising();
      await manager.stop();
      setIsAdvertising(false);
      addLog('Stopped successfully.');
    } catch (err: any) {
      addLog(`Error stopping: ${err.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.header}>
        <Text style={styles.title}>BLE Peripheral Example</Text>
        <Text style={styles.subtitle}>State: {btState} | Adv: {isAdvertising ? 'Active' : 'Inactive'}</Text>
      </View>

      <View style={styles.buttonRow}>
        <TouchableOpacity
          style={[styles.button, styles.startButton, isAdvertising && styles.buttonDisabled]}
          disabled={isAdvertising}
          onPress={startAdvertising}
        >
          <Text style={styles.buttonText}>Start Advertising</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.button, styles.stopButton, !isAdvertising && styles.buttonDisabled]}
          disabled={!isAdvertising}
          onPress={stopAdvertising}
        >
          <Text style={styles.buttonText}>Stop Advertising</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.logsContainer}>
        <Text style={styles.logsHeader}>Logs:</Text>
        <ScrollView style={styles.logsScroll}>
          {logs.map((log, index) => (
            <Text key={index} style={styles.logText}>
              {log}
            </Text>
          ))}
        </ScrollView>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F7FB',
  },
  header: {
    padding: 20,
    backgroundColor: '#FFFFFF',
    borderBottomWidth: 1,
    borderBottomColor: '#E2E8F0',
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#1E293B',
  },
  subtitle: {
    fontSize: 14,
    color: '#64748B',
    marginTop: 4,
  },
  buttonRow: {
    flexDirection: 'row',
    padding: 16,
    gap: 12,
  },
  button: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
  },
  startButton: {
    backgroundColor: '#10B981',
  },
  stopButton: {
    backgroundColor: '#EF4444',
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
  logsContainer: {
    flex: 1,
    margin: 16,
    padding: 16,
    backgroundColor: '#0F172A',
    borderRadius: 8,
  },
  logsHeader: {
    fontSize: 14,
    fontWeight: '600',
    color: '#94A3B8',
    marginBottom: 8,
  },
  logsScroll: {
    flex: 1,
  },
  logText: {
    color: '#E2E8F0',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 12,
    marginVertical: 2,
  },
});
