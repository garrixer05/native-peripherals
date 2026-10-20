import NativeBlePeripheral, {
  type CharacteristicDef,
} from './NativeRNBlePeripheral'
import { normalizeUuid } from './uuid'

export type Permission =
  /** Centrals can read the value. */
  | 'readable'
  /** Centrals can write the value. */
  | 'writeable'
  /** Only encrypted (bonded) links can read the value. */
  | 'readEncryptionRequired'
  /** Only encrypted (bonded) links can write the value. */
  | 'writeEncryptionRequired'

export type Property =
  | 'broadcast'
  | 'read'
  | 'writeWithoutResponse'
  | 'write'
  | 'notify'
  | 'indicate'
  | 'authenticatedSignedWrites'
  | 'extendedProperties'
  /** Enabling notifications requires an encrypted link. */
  | 'notifyEncryptionRequired'
  /** Enabling indications requires an encrypted link. */
  | 'indicateEncryptionRequired'

export type SubscriptionMode = 'notify' | 'indicate'

export type ReadRequest = { deviceId: string }

export type WriteRequest = {
  deviceId: string
  /** Base64-encoded, complete value. */
  value: string
  responseNeeded: boolean
}

export type CharacteristicParams = {
  uuid: string
  properties: Property[]
  /** Defaults to `readable` / `writeable`, derived from `properties`. */
  permissions?: Permission[]
  /**
   * Base64-encoded static value. Served by native code without calling JS, unless
   * `onReadRequest` is provided.
   */
  value?: string
  /**
   * Supply the (full, base64) value on every read. Native handles offsets and long reads.
   * Throw an `AttError` to answer with a specific ATT error.
   */
  onReadRequest?: (request: ReadRequest) => string | Promise<string>
  /**
   * Handle a complete write. Without a handler, native stores the value itself.
   * Throw an `AttError` to reject the write.
   */
  onWriteRequest?: (request: WriteRequest) => void | Promise<void>
  onSubscribe?: (deviceId: string, mode: SubscriptionMode) => void
  onUnsubscribe?: (deviceId: string) => void
}

export default class Characteristic {
  readonly uuid: string
  readonly properties: Property[]
  readonly permissions?: Permission[]

  /** Base64 value. Kept in sync by `notify(value)`. */
  value?: string

  private readonly readHandler?: CharacteristicParams['onReadRequest']
  private readonly writeHandler?: CharacteristicParams['onWriteRequest']
  private readonly subscribeHandler?: CharacteristicParams['onSubscribe']
  private readonly unsubscribeHandler?: CharacteristicParams['onUnsubscribe']
  private _serviceUuid?: string

  constructor(params: CharacteristicParams) {
    if (!params.uuid) throw new Error('Characteristic UUID is required!')

    this.uuid = normalizeUuid(params.uuid)
    this.properties = params.properties
    this.permissions = params.permissions
    this.value = params.value
    this.readHandler = params.onReadRequest
    this.writeHandler = params.onWriteRequest
    this.subscribeHandler = params.onSubscribe
    this.unsubscribeHandler = params.onUnsubscribe
  }

  /** UUID of the owning service (set when the characteristic is added to a Service). */
  get serviceUuid(): string | undefined {
    return this._serviceUuid
  }

  /** @internal Called by Service. */
  _attach(serviceUuid: string) {
    if (this._serviceUuid && this._serviceUuid !== serviceUuid) {
      throw new Error(
        `Characteristic ${this.uuid} already belongs to service ${this._serviceUuid}`
      )
    }
    this._serviceUuid = serviceUuid
  }

  /** @internal Plain-data form sent over the bridge (no functions, no class instances). */
  toDef(): CharacteristicDef {
    return {
      uuid: this.uuid,
      properties: this.properties,
      permissions: this.permissions ?? [],
      value: this.value,
      dynamicRead: this.readHandler !== undefined,
      dynamicWrite: this.writeHandler !== undefined,
    }
  }

  /** @internal */
  async _handleRead(request: ReadRequest): Promise<string> {
    if (!this.readHandler) return this.value ?? ''
    return this.readHandler(request)
  }

  /** @internal */
  async _handleWrite(request: WriteRequest): Promise<void> {
    if (!this.writeHandler) {
      this.value = request.value
      return
    }
    await this.writeHandler(request)
  }

  /** @internal */
  _handleSubscribe(deviceId: string, mode: SubscriptionMode) {
    this.subscribeHandler?.(deviceId, mode)
  }

  /** @internal */
  _handleUnsubscribe(deviceId: string) {
    this.unsubscribeHandler?.(deviceId)
  }

  /**
   * Update the value (if given) and send it to subscribed centrals, or only to `deviceId`.
   * Notification vs. indication follows what each central enabled. Resolves on delivery.
   */
  notify(value?: string, options?: { deviceId?: string }): Promise<void> {
    if (!this._serviceUuid) {
      return Promise.reject(
        new Error('Add the characteristic to a Service before calling notify()')
      )
    }
    if (value !== undefined) this.value = value

    return NativeBlePeripheral.notify(
      this._serviceUuid,
      this.uuid,
      this.value ?? '',
      options?.deviceId
    )
  }
}
