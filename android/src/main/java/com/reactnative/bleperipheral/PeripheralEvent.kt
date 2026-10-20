package com.reactnative.bleperipheral

import java.util.UUID

/**
 * Streaming events produced by [com.reactnative.bleperipheral.gatt.GattServer] and
 * [com.reactnative.bleperipheral.peripheral.BlePeripheral]. The bridge module collects
 * these from `BlePeripheral.events` and forwards them to JS.
 *
 * Only primitive-ish types are used so this file has no dependency on the gatt package.
 */
sealed class PeripheralEvent {

    /** A central wants to read a characteristic whose value is supplied by JS. */
    data class ReadRequest(
        val requestId: String,
        val deviceId: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
    ) : PeripheralEvent()

    /** A (fully assembled) write to a characteristic whose handler lives in JS. */
    class WriteRequest(
        val requestId: String,
        val deviceId: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val responseNeeded: Boolean,
    ) : PeripheralEvent()

    /** [mode] is "notify" or "indicate". */
    data class Subscribed(
        val deviceId: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val mode: String,
    ) : PeripheralEvent()

    data class Unsubscribed(
        val deviceId: String,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
    ) : PeripheralEvent()

    data class DeviceConnected(val deviceId: String) : PeripheralEvent()
    data class DeviceDisconnected(val deviceId: String) : PeripheralEvent()
    data class MtuChanged(val deviceId: String, val mtu: Int) : PeripheralEvent()

    /** One of: poweredOn, poweredOff, resetting, unauthorized, unsupported, unknown. */
    data class StateChanged(val state: String) : PeripheralEvent()
}
