package com.reactnative.bleperipheral.gatt

import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * Wraps a [BluetoothGattService] together with its [GattCharacteristic]s.
 *
 * Nested services are expressed as [includedServiceUuids]: the included services must already
 * be registered on the [GattServer] when this one is added (the JS layer sends them first).
 */
class GattService(
    val uuid: UUID,
    val primary: Boolean,
    val characteristics: List<GattCharacteristic>,
    val includedServiceUuids: List<UUID> = emptyList(),
) {

    init {
        require(characteristics.map { it.uuid }.toSet().size == characteristics.size) {
            "Service $uuid contains duplicate characteristic UUIDs"
        }
    }

    val native: BluetoothGattService = BluetoothGattService(
        uuid,
        if (primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY,
    ).also { service ->
        characteristics.forEach { service.addCharacteristic(it.native) }
    }

    /** Declares [other] as an included service of this one. */
    fun include(other: GattService) {
        native.addService(other.native)
    }
}
