/** ATT status strings accepted by native `respond()`. */
export type AttStatus =
  | 'success'
  | 'invalidHandle'
  | 'readNotPermitted'
  | 'writeNotPermitted'
  | 'insufficientAuthentication'
  | 'requestNotSupported'
  | 'invalidOffset'
  | 'insufficientAuthorization'
  | 'invalidAttributeLength'
  | 'unlikelyError'
  | 'insufficientEncryption'

/**
 * Throw (or reject with) this from `onReadRequest` / `onWriteRequest` to answer the central
 * with a specific ATT error. Any other error is answered as 'unlikelyError'.
 */
export class AttError extends Error {
  readonly status: Exclude<AttStatus, 'success'>

  constructor(status: Exclude<AttStatus, 'success'>, message?: string) {
    super(message ?? `ATT error: ${status}`)
    Object.setPrototypeOf(this, AttError.prototype)
    this.name = 'AttError'
    this.status = status
  }
}
