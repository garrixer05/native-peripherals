import Manager from './Manager'

export { default as Manager } from './Manager'
export { default as Service } from './Service'
export { default as Characteristic } from './Characteristic'
export { AttError } from './AttError'

export type { ManagerState } from './Manager'
export type { AttStatus } from './AttError'
export type {
  Permission,
  Property,
  ReadRequest,
  WriteRequest,
  SubscriptionMode,
  CharacteristicParams,
} from './Characteristic'

/** Shared instance, so `import Peripheral from 'native-peripherals'` keeps working. */
export default new Manager()