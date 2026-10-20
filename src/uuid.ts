const BASE_SUFFIX = '-0000-1000-8000-00805f9b34fb'

/**
 * Normalizes a UUID to the lowercase 128-bit form native uses in events, expanding
 * 16-bit ("180d") and 32-bit Bluetooth SIG UUIDs. Use it for every UUID comparison.
 */
export function normalizeUuid(uuid: string): string {
  const s = uuid.trim().toLowerCase()
  if (/^[0-9a-f]{4}$/.test(s)) return `0000${s}${BASE_SUFFIX}`
  if (/^[0-9a-f]{8}$/.test(s)) return `${s}${BASE_SUFFIX}`
  return s
}
