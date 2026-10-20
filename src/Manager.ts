import type { EventSubscription } from 'react-native'
import Native, {
  type ReadRequestEvent,
  type SubscriptionEvent,
  type WriteRequestEvent,
} from './NativeRNBlePeripheral'
import type Characteristic from './Characteristic'
import type { SubscriptionMode } from './Characteristic'
import type Service from './Service'
import { AttError, type AttStatus } from './AttError'

export type ManagerState =
  | 'poweredOff'
  | 'poweredOn'
  | 'resetting'
  | 'unauthorized'
  | 'unknown'
  | 'unsupported'

const keyOf = (serviceUuid: string, characteristicUuid: string) =>
  `${serviceUuid}/${characteristicUuid}`

const statusOf = (error: unknown): Exclude<AttStatus, 'success'> =>
  error instanceof AttError ? error.status : 'unlikelyError'

export default class Manager {
  private handlers = new Map<string, Characteristic>()
  private subscriptions: EventSubscription[] = []
  private started = false

  /**
   * Open the GATT server and begin handling requests.
   *
   * _[Android 12+]_ The app must hold BLUETOOTH_CONNECT (and BLUETOOTH_ADVERTISE before
   * `startAdvertising`) at runtime. Missing permissions reject with `E_PERMISSION`.
   *
   * Call this before `addService`. Idempotent.
   */
  async start(): Promise<void> {
    if (this.started) return

    this.attachListeners()
    try {
      await Native.start()
      this.started = true
    } catch (error) {
      this.detachListeners()
      throw error
    }
  }

  /** Stop advertising, disconnect all centrals and close the GATT server. */
  async stop(): Promise<void> {
    this.reset()
    await Native.stop()
  }

  /**
   * Add a service, its characteristics and nested services to the local GATT database.
   * Requires `start()`. Resolves once the Bluetooth stack has registered every service.
   */
  async addService(service: Service): Promise<void> {
    const characteristics = service.allCharacteristics()
    // Register handlers first: a central can read the instant native registers the service.
    characteristics.forEach(ch => this.handlers.set(keyOf(ch.serviceUuid!, ch.uuid), ch))

    const added: string[] = []
    try {
      for (const def of service.toDefs()) {
        await Native.addService(def)
        added.push(def.uuid)
      }
    } catch (error) {
      for (const uuid of added.reverse()) {
        await Native.removeService(uuid).catch(() => {})
      }
      characteristics.forEach(ch => this.handlers.delete(keyOf(ch.serviceUuid!, ch.uuid)))
      throw error
    }
  }

  /** Remove a service (and its nested services) from the local GATT database. */
  async removeService(service: Service): Promise<void> {
    for (const uuid of service.allServiceUuids().reverse()) {
      await Native.removeService(uuid)
    }
    service
      .allCharacteristics()
      .forEach(ch => this.handlers.delete(keyOf(ch.serviceUuid!, ch.uuid)))
  }

  /** Remove all services from the local GATT database. */
  async removeAllServices(): Promise<void> {
    await Native.removeAllServices()
    this.handlers.clear()
  }

  /**
   * Advertise so centrals can discover this peripheral. Requires `start()`.
   *
   * _[Android]_ The advertisement carries up to 31 bytes, so prefer a few short or 16-bit
   * service UUIDs. `name` is sent in the scan response and temporarily renames the device's
   * Bluetooth adapter until `stopAdvertising()`.
   */
  startAdvertising(data: { name?: string; serviceUuids: string[] }): Promise<void> {
    return Native.startAdvertising({
      name: data.name,
      serviceUuids: data.serviceUuids,
    })
  }

  stopAdvertising(): Promise<void> {
    return Native.stopAdvertising()
  }

  isAdvertising(): Promise<boolean> {
    return Native.isAdvertising()
  }

  /** Addresses of currently connected centrals. */
  getConnectedDevices(): Promise<string[]> {
    return Native.getConnectedDevices()
  }

  /**
   * Observe Bluetooth availability. The listener is called once with the current state, then
   * on every change. When Bluetooth turns off the GATT database is lost: after it returns to
   * `poweredOn`, call `start()` and re-add your services.
   */
  onStateChanged(listener: (state: ManagerState) => void): EventSubscription {
    Native.getState().then(state => listener(state as ManagerState))
    return Native.onStateChanged(({ state }) => listener(state as ManagerState))
  }

  onDeviceConnected(listener: (deviceId: string) => void): EventSubscription {
    return Native.onDeviceConnected(({ deviceId }) => listener(deviceId))
  }

  onDeviceDisconnected(listener: (deviceId: string) => void): EventSubscription {
    return Native.onDeviceDisconnected(({ deviceId }) => listener(deviceId))
  }

  onMtuChanged(listener: (deviceId: string, mtu: number) => void): EventSubscription {
    return Native.onMtuChanged(({ deviceId, mtu }) => listener(deviceId, mtu))
  }

  // --- Internals ----------------------------------------------------------------------

  private attachListeners() {
    this.subscriptions = [
      Native.onReadRequest(e => this.handleRead(e)),
      Native.onWriteRequest(e => this.handleWrite(e)),
      Native.onSubscribed(e => this.handleSubscribed(e)),
      Native.onUnsubscribed(e => this.find(e)?._handleUnsubscribe(e.deviceId)),
      // Native tears the GATT server down when Bluetooth turns off; mirror that here.
      Native.onStateChanged(({ state }) => {
        if (state !== 'poweredOn') this.reset()
      }),
    ]
  }

  private detachListeners() {
    this.subscriptions.forEach(s => s.remove())
    this.subscriptions = []
  }

  private reset() {
    this.detachListeners()
    this.handlers.clear()
    this.started = false
  }

  private find(e: { serviceUuid: string; characteristicUuid: string }) {
    return this.handlers.get(keyOf(e.serviceUuid, e.characteristicUuid))
  }

  private async handleRead(e: ReadRequestEvent) {
    const ch = this.find(e)
    if (!ch) return Native.respond(e.requestId, 'invalidHandle')

    try {
      const value = await ch._handleRead({ deviceId: e.deviceId })
      Native.respond(e.requestId, 'success', value)
    } catch (error) {
      Native.respond(e.requestId, statusOf(error))
    }
  }

  private async handleWrite(e: WriteRequestEvent) {
    const ch = this.find(e)
    if (!ch) return Native.respond(e.requestId, 'invalidHandle')

    try {
      await ch._handleWrite({
        deviceId: e.deviceId,
        value: e.value,
        responseNeeded: e.responseNeeded,
      })
      Native.respond(e.requestId, 'success')
    } catch (error) {
      Native.respond(e.requestId, statusOf(error))
    }
  }

  private handleSubscribed(e: SubscriptionEvent) {
    this.find(e)?._handleSubscribe(
      e.deviceId,
      (e.mode as SubscriptionMode | undefined) ?? 'notify'
    )
  }
}
