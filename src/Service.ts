import type { ServiceDef } from './NativeRNBlePeripheral'
import Characteristic from './Characteristic'
import { normalizeUuid } from './uuid'

export default class Service {
  readonly uuid: string
  readonly primary: boolean
  readonly characteristics: Characteristic[]
  /** Nested (included) services. */
  readonly services: Service[]

  constructor(params: {
    uuid: string
    /** Defaults to `true`. */
    primary?: boolean
    characteristics?: Characteristic[]
    services?: Service[]
  }) {
    if (!params.uuid) throw new Error('Service UUID is required!')

    this.uuid = normalizeUuid(params.uuid)
    this.primary = params.primary ?? true
    this.characteristics = params.characteristics ?? []
    this.services = params.services ?? []

    this.characteristics.forEach(ch => ch._attach(this.uuid))
  }

  /** @internal Flat list for native: nested services first, so each is registered before its parent. */
  toDefs(): ServiceDef[] {
    const nested = ([] as ServiceDef[]).concat(
      ...this.services.map(s => s.toDefs())
    )
    return [
      ...nested,
      {
        uuid: this.uuid,
        primary: this.primary,
        characteristics: this.characteristics.map(ch => ch.toDef()),
        includedServiceUuids: this.services.map(s => s.uuid),
      },
    ]
  }

  /** @internal This service's and all nested services' UUIDs, nested first. */
  allServiceUuids(): string[] {
    return toDefsUuids(this)
  }

  /** @internal Every characteristic in this service and in nested services. */
  allCharacteristics(): Characteristic[] {
    return ([] as Characteristic[]).concat(
      ...this.services.map(s => s.allCharacteristics()),
      this.characteristics
    )
  }
}

function toDefsUuids(service: Service): string[] {
  return service.toDefs().map(def => def.uuid)
}
