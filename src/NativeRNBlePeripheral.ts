import type { TurboModule, CodegenTypes } from 'react-native'
import { TurboModuleRegistry } from 'react-native'

/**
 * Codegen spec: the single source of truth for the JS <-> native contract.
 *
 * Codegen cannot express unions or recursive types, so Property, Permission, status and
 * mode are plain strings here. The public classes (Characteristic, Service, ...) keep the
 * strict types, and native validates the strings.
 *
 * All UUIDs crossing this boundary are lowercase 128-bit strings (see ./uuid.ts).
 * All binary values are base64 strings.
 */

/** A service, flattened: nested services are listed separately and referenced by UUID. */
export type ServiceDef = {
  uuid: string
  primary: boolean
  characteristics: CharacteristicDef[]
  includedServiceUuids: string[]
}

export type CharacteristicDef = {
  uuid: string
  properties: string[]
  permissions: string[]
  /** Static value, served natively when `dynamicRead` is false. */
  value?: string
  /** True if JS supplies the value on each read (a READ_REQUEST event is emitted). */
  dynamicRead: boolean
  /** True if JS handles writes (a WRITE_REQUEST event is emitted). */
  dynamicWrite: boolean
}

export type AdvertiseOptions = {
  /**
   * Android has no per-advertisement name: this temporarily renames the device's Bluetooth
   * adapter and is restored by stopAdvertising().
   */
  name?: string
  serviceUuids: string[]
}

export type ReadRequestEvent = {
  requestId: string
  deviceId: string
  serviceUuid: string
  characteristicUuid: string
}

export type WriteRequestEvent = {
  requestId: string
  deviceId: string
  serviceUuid: string
  characteristicUuid: string
  /** Complete value (long writes are assembled natively). */
  value: string
  responseNeeded: boolean
}

export type SubscriptionEvent = {
  deviceId: string
  serviceUuid: string
  characteristicUuid: string
  /** 'notify' or 'indicate' (unused for unsubscribe events). */
  mode?: string
}

export type DeviceEvent = { deviceId: string }
export type MtuEvent = { deviceId: string; mtu: number }
export type StateEvent = { state: string }

export interface Spec extends TurboModule {
  // Lifecycle
  start(): Promise<void>
  stop(): Promise<void>

  // Services
  addService(service: ServiceDef): Promise<void>
  removeService(uuid: string): Promise<void>
  removeAllServices(): Promise<void>

  // Advertising
  startAdvertising(options: AdvertiseOptions): Promise<void>
  stopAdvertising(): Promise<void>
  isAdvertising(): Promise<boolean>

  // Data
  /**
   * Stores `value` as the characteristic's cached value and sends it to subscribed centrals
   * (or only `deviceId`). Notify vs. indicate follows what each central enabled.
   * Resolves once Android reports delivery.
   */
  notify(
    serviceUuid: string,
    characteristicUuid: string,
    value: string,
    deviceId?: string
  ): Promise<void>
  /** Answers a READ_REQUEST / WRITE_REQUEST event. `status` is an AttStatus string. */
  respond(requestId: string, status: string, value?: string): void

  // State
  getState(): Promise<string>
  getConnectedDevices(): Promise<string[]>

  // Events
  readonly onReadRequest: CodegenTypes.EventEmitter<ReadRequestEvent>
  readonly onWriteRequest: CodegenTypes.EventEmitter<WriteRequestEvent>
  readonly onSubscribed: CodegenTypes.EventEmitter<SubscriptionEvent>
  readonly onUnsubscribed: CodegenTypes.EventEmitter<SubscriptionEvent>
  readonly onDeviceConnected: CodegenTypes.EventEmitter<DeviceEvent>
  readonly onDeviceDisconnected: CodegenTypes.EventEmitter<DeviceEvent>
  readonly onMtuChanged: CodegenTypes.EventEmitter<MtuEvent>
  readonly onStateChanged: CodegenTypes.EventEmitter<StateEvent>
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNBlePeripheral')
