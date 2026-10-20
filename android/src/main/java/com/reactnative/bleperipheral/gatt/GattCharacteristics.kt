package com.reactnative.bleperipheral.gatt

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Maps the JS `Property` strings to Android property flags. */
enum class CharacteristicProperty(val jsName: String, val flag: Int) {
    BROADCAST("broadcast", BluetoothGattCharacteristic.PROPERTY_BROADCAST),
    READ("read", BluetoothGattCharacteristic.PROPERTY_READ),
    WRITE_WITHOUT_RESPONSE("writeWithoutResponse", BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE),
    WRITE("write", BluetoothGattCharacteristic.PROPERTY_WRITE),
    NOTIFY("notify", BluetoothGattCharacteristic.PROPERTY_NOTIFY),
    INDICATE("indicate", BluetoothGattCharacteristic.PROPERTY_INDICATE),
    AUTHENTICATED_SIGNED_WRITES("authenticatedSignedWrites", BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE),
    EXTENDED_PROPERTIES("extendedProperties", BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS),

    // iOS-only flags. Android has no equivalent property bit; we translate them
    // into encrypted permissions on the CCCD descriptor instead (see buildNative()).
    NOTIFY_ENCRYPTION_REQUIRED("notifyEncryptionRequired", 0),
    INDICATE_ENCRYPTION_REQUIRED("indicateEncryptionRequired", 0);

    companion object {
        fun fromJs(name: String): CharacteristicProperty =
            values().firstOrNull { it.jsName == name }
                ?: throw IllegalArgumentException("Unknown characteristic property: $name")
    }
}

/** Maps the JS `Permission` strings to Android permission flags. */
enum class CharacteristicPermission(val jsName: String, val flag: Int) {
    READABLE("readable", BluetoothGattCharacteristic.PERMISSION_READ),
    WRITEABLE("writeable", BluetoothGattCharacteristic.PERMISSION_WRITE),
    READ_ENCRYPTION_REQUIRED("readEncryptionRequired", BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED),
    WRITE_ENCRYPTION_REQUIRED("writeEncryptionRequired", BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);

    companion object {
        fun fromJs(name: String): CharacteristicPermission =
            values().firstOrNull { it.jsName == name }
                ?: throw IllegalArgumentException("Unknown characteristic permission: $name")
    }
}

/** What a connected central has enabled through the CCCD descriptor. */
enum class SubscriptionState { NONE, NOTIFY, INDICATE }

/**
 * Wraps a [BluetoothGattCharacteristic] plus the state Android doesn't track for us:
 * the current value and which centrals are subscribed.
 */
class GattCharacteristic(
    val uuid: UUID,
    val properties: Set<CharacteristicProperty>,
    val permissions: Set<CharacteristicPermission>,
    initialValue: ByteArray? = null
) {

    /** Current value. Updated by writes and read back by read requests. */
    @Volatile
    var value: ByteArray = initialValue ?: ByteArray(0)

    /** deviceAddress -> subscription state. Only populated for subscribed devices. */
    private val subscriptions = ConcurrentHashMap<String, SubscriptionState>()

    /** The underlying Android object, built once and handed to [GattService]. */
    val native: BluetoothGattCharacteristic by lazy { buildNative() }

    private val supportsSubscription: Boolean
        get() = CharacteristicProperty.NOTIFY in properties ||
            CharacteristicProperty.INDICATE in properties

    // --- Building -----------------------------------------------------------

    private fun buildNative(): BluetoothGattCharacteristic {
        val propertyMask = properties.fold(0) { acc, p -> acc or p.flag }
        val permissionMask = permissions.fold(0) { acc, p -> acc or p.flag }

        val characteristic = BluetoothGattCharacteristic(uuid, propertyMask, permissionMask)

        // Android requires a CCCD descriptor for notify/indicate to work.
        // Without it, centrals can't subscribe, and notifications are silently dropped.
        if (supportsSubscription) {
            val encrypted = CharacteristicProperty.NOTIFY_ENCRYPTION_REQUIRED in properties ||
                CharacteristicProperty.INDICATE_ENCRYPTION_REQUIRED in properties

            val cccdPermissions = BluetoothGattDescriptor.PERMISSION_READ or
                if (encrypted) BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                else BluetoothGattDescriptor.PERMISSION_WRITE

            characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, cccdPermissions))
        }

        return characteristic
    }

    // --- Read / write helpers (called by GattServer) ---------------------------

    /** Returns the value starting at [offset], or null if the offset is out of range. */
    fun readValue(offset: Int): ByteArray? {
        val current = value
        if (offset < 0 || offset > current.size) return null
        return current.copyOfRange(offset, current.size)
    }

    // --- Subscriptions (CCCD) ----------------------------------------------------

    /** Interprets a CCCD write from [deviceAddress] and records the new state. */
    fun onCccdWrite(deviceAddress: String, cccdValue: ByteArray): SubscriptionState {
        val state = when {
            cccdValue.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> SubscriptionState.NOTIFY
            cccdValue.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> SubscriptionState.INDICATE
            else -> SubscriptionState.NONE
        }
        if (state == SubscriptionState.NONE) subscriptions.remove(deviceAddress)
        else subscriptions[deviceAddress] = state
        return state
    }

    fun subscriptionFor(deviceAddress: String): SubscriptionState =
        subscriptions[deviceAddress] ?: SubscriptionState.NONE

    /** Addresses of all centrals currently subscribed (notify or indicate). */
    fun subscribedDevices(): Set<String> = subscriptions.keys.toSet()

    /** Call when a central disconnects so we don't notify a stale address. */
    fun onDeviceDisconnected(deviceAddress: String) {
        subscriptions.remove(deviceAddress)
    }

    companion object {
        /** Client Characteristic Configuration Descriptor, defined by the BLE spec. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Builds a characteristic from the string lists the JS layer sends. */
        fun fromJs(
            uuid: String,
            properties: List<String>?,
            permissions: List<String>?,
            initialValue: ByteArray?
        ): GattCharacteristic = GattCharacteristic(
            uuid = UUID.fromString(uuid),
            properties = properties.orEmpty().map(CharacteristicProperty::fromJs).toSet(),
            permissions = permissions.orEmpty().map(CharacteristicPermission::fromJs).toSet(),
            initialValue = initialValue
        )
    }
}