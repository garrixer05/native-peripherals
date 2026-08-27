# native-peripherals

React Native library for building BLE peripherals.

_Note:_ If you want to write a BLE Central application (that connects to BLE peripherals such as a watch or heart rate monitor), check out [react-native-ble-plx](https://github.com/Polidea/react-native-ble-plx/) or [react-native-ble-manager](https://github.com/innoveit/react-native-ble-manager/) instead.

## Installation

Using npm:

```sh
npm install --save native-peripherals
```

or using yarn:

```sh
yarn add native-peripherals
```

Then follow the instructions for your platform to link native-peripherals into your project:

<details>
  <summary><b>iOS</b></summary>

#### React Native 0.60 and above

Linking is automatic. Just run `pod install` in the `ios` directory.

#### Required permissions

You must add `NSBluetoothAlwaysUsageDescription` and `NSBluetoothPeripheralUsageDescription` to your `Info.plist` file.

</details>

<details>
  <summary><b>Android</b></summary>

Android support is not implemented yet.

#### Required permissions (Android 12+)

Make sure to request `android.permission.BLUETOOTH_ADVERTISE` and `android.permission.BLUETOOTH_CONNECT` permissions at runtime.

</details>

## Usage

```js
import Peripheral, { Service, Characteristic } from 'native-peripherals'

Peripheral.onStateChanged(state => {
  // wait until Bluetooth is ready
  if (state === 'poweredOn') {
    // first, define a characteristic with a value
    const ch = new Characteristic({
      uuid: '...',
      value: '...', // Base64-encoded string
      properties: ['read', 'write'],
      permissions: ['readable', 'writeable'],
    })

    // add the characteristic to a service
    const service = new Service({
      uuid: '...',
      characteristics: [ch],
    })

    // register GATT services that your device provides
    Peripheral.addService(service).then(() => {
      // start advertising to make your device discoverable
      Peripheral.startAdvertising({
        name: 'My BLE device',
        serviceUuids: ['...'],
      })
    })
  }
})
```

Note: `addService` and `startAdvertising` are conceptually independent events. A BLE peripheral can start advertising regardless of whether it has defined any services. Conversely, you can define services which can be used by previously defined clients. However, in most cases, you'll want to do both.

To end a session, you can call `Peripheral.stopAdvertising`. You can also check if you're currently advertising with `Peripheral.isAdvertising`.

### Dynamic Value

If you want to change the characteristic value dynamically, instead of providing `value`, implement `onReadRequest` and `onWriteRequest` (if your characteristic supports `read` and `write` operations):

```js
new Characteristic({
  uuid: '...',
  properties: ['read', 'write'],
  permissions: ['readable', 'writeable'],
  onReadRequest: async (offset?: number) => {
    const value = '...' // calculate the value
    return value // you can also return a promise
  },
  onWriteRequest: async (value: string, offset?: number) => {
    // store or do something with the value
    this.value = value
  },
})
```

### Notifications

If the value in your characteristic changes frequently, BLE clients may want to subscribe to be notified about the changes. Subscription logic is handled for you automatically, you just need to do two things:

1. Include `notify` in the list of properties:

   ```js
   const ch = new Characteristic({
     // ...
     properties: ['notify', ...]
   })
   ```

2. Trigger `notify` with a value to send to subscribed clients:

   ```js
   const value = '...'
   ch.notify(value)
   ```

3. (optional) You may also track when clients subscribe to notifications, for example to avoid recomputing the value when no one is listening. This is optional since a call to `notify` will deliver to all subscribers by default, and silently no-op when there are none.

   ```js
   const listeners = 0
   const ch = new Characteristic({
     // ...
     onSubscribe() {
       listeners++;
     }
     onUnsubscribe() {
       listeners--;
     }
   })

   // sometime later
   if (listeners) {
     const value = '...' // calculate value
     ch.notify(value)
   }
   ```

### Base64

This library expects the value to be a Base64-encoded string.

Depending on your use case, you may want to add a library to help you convert to Base64:

- If you work with binary data, we recommend you internally store an `ArrayBuffer` or `Uint8Array` and convert it using [base64-arraybuffer](https://www.npmjs.com/package/base64-arraybuffer) or [base64-js](https://www.npmjs.com/package/base64-js)
- For dealing with complex binary structures, try [jBinary](https://github.com/jDataView/jBinary)
- If you want to send text data, we recommend you use [base64.js](https://www.npmjs.com/package/js-base64)
