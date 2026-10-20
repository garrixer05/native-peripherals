package com.reactnative.bleperipheral.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.reactnative.bleperipheral.BleException
import com.reactnative.bleperipheral.ErrorCodes
import com.reactnative.bleperipheral.PeripheralEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the [BluetoothGattServer] and translates its callback API into:
 *  - suspend functions for one-shot operations (addService, notify), and
 *  - [PeripheralEvent]s (via [emit]) for the stream of requests/connection changes.
 *
 * Requests that need an answer from JS (reads/writes of "dynamic" characteristics) are
 * registered in [pendingRequests] under a string id. [respond] completes them; if JS does not
 * answer within [requestTimeoutMs] the central receives an error instead of hanging.
 *
 * BLUETOOTH_CONNECT must be granted before [open] is called; BlePeripheral checks this.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope,
    private val emit: (PeripheralEvent) -> Unit,
    private val requestTimeoutMs: Long = 10_000L,
) {

    private class Response(val status: Int, val value: ByteArray?)
    private class PendingRequest(val deviceAddress: String, val deferred: CompletableDeferred<Response>)
    private class PendingNotification(val deviceAddress: String, val ack: CompletableDeferred<Int>)
    private class PreparedChunk(
        val characteristic: GattCharacteristic,
        val serviceUuid: UUID,
        val offset: Int,
        val value: ByteArray,
    )

    @Volatile
    private var server: BluetoothGattServer? = null

    private val services = ConcurrentHashMap<UUID, GattService>()
    private val characteristicIndex = ConcurrentHashMap<String, GattCharacteristic>()
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()

    /** Value snapshot per (device, characteristic) so a multi-chunk read sees a stable value. */
    private val readSnapshots = ConcurrentHashMap<String, ByteArray>()
    private val preparedWrites = ConcurrentHashMap<String, CopyOnWriteArrayList<PreparedChunk>>()

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val requestCounter = AtomicLong(0)

    private val addServiceMutex = Mutex()
    private val pendingServiceAdds = ConcurrentHashMap<UUID, CompletableDeferred<Int>>()

    // Android allows a single notification in flight; wait for onNotificationSent before the next.
    private val notifyMutex = Mutex()
    @Volatile
    private var pendingNotification: PendingNotification? = null

    // --- Lifecycle ----------------------------------------------------------------------

    fun open() {
        if (server != null) return
        server = bluetoothManager.openGattServer(context, callback)
            ?: throw BleException(ErrorCodes.OPEN_FAILED, "Could not open the GATT server (is Bluetooth on?)")
    }

    fun close() {
        val srv = server ?: return
        server = null
        failEverythingPending()
        try {
            srv.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server", e)
        }
        services.clear()
        characteristicIndex.clear()
        devices.clear()
        mtus.clear()
        readSnapshots.clear()
        preparedWrites.clear()
    }

    fun connectedDeviceIds(): List<String> = devices.keys.toList()

    private fun requireServer(): BluetoothGattServer =
        server ?: throw BleException(ErrorCodes.NOT_STARTED, "Peripheral is not started. Call start() first.")

    // --- Services -----------------------------------------------------------------------

    /** Registers [service] and suspends until Android confirms it via onServiceAdded. */
    suspend fun addService(service: GattService) = addServiceMutex.withLock {
        val srv = requireServer()
        if (services.containsKey(service.uuid)) {
            throw BleException(ErrorCodes.SERVICE_EXISTS, "Service ${service.uuid} was already added")
        }
        for (includedUuid in service.includedServiceUuids) {
            val included = services[includedUuid] ?: throw BleException(
                ErrorCodes.NOT_FOUND,
                "Included service $includedUuid must be added before ${service.uuid}",
            )
            service.include(included)
        }

        // Index first: a central may read the moment the stack registers the service.
        service.characteristics.forEach { characteristicIndex[indexKey(service.uuid, it.uuid)] = it }

        val deferred = CompletableDeferred<Int>()
        pendingServiceAdds[service.uuid] = deferred

        val status: Int? = try {
            if (!srv.addService(service.native)) {
                throw BleException(ErrorCodes.SERVICE_FAILED, "The Bluetooth stack rejected service ${service.uuid}")
            }
            withTimeoutOrNull(SERVICE_ADD_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            unindex(service)
            throw e
        } finally {
            pendingServiceAdds.remove(service.uuid)
        }

        if (status != BLUETOOTH_GATT_SUCCESS) {
            unindex(service)
            throw BleException(
                ErrorCodes.SERVICE_FAILED,
                if (status == null) "Timed out adding service ${service.uuid}"
                else "Adding service ${service.uuid} failed with status $status",
            )
        }
        services[service.uuid] = service
    }

    fun removeService(uuid: UUID) {
        val srv = requireServer()
        val service = services.remove(uuid)
            ?: throw BleException(ErrorCodes.NOT_FOUND, "Service $uuid is not registered")
        unindex(service)
        srv.removeService(service.native)
    }

    fun removeAllServices() {
        val srv = requireServer()
        srv.clearServices()
        services.clear()
        characteristicIndex.clear()
        readSnapshots.clear()
    }

    private fun unindex(service: GattService) {
        service.characteristics.forEach { characteristicIndex.remove(indexKey(service.uuid, it.uuid)) }
    }

    // --- Notifications ------------------------------------------------------------------

    /**
     * Updates the characteristic's cached value and sends it to [deviceAddress], or to every
     * subscribed device when null. Whether a notification or an indication is used is decided by
     * what each central enabled in its CCCD. Suspends until Android reports delivery.
     */
    suspend fun notify(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray, deviceAddress: String?) {
        val srv = requireServer()
        val characteristic = characteristicIndex[indexKey(serviceUuid, characteristicUuid)]
            ?: throw BleException(ErrorCodes.NOT_FOUND, "Characteristic $characteristicUuid not found in $serviceUuid")

        val targets: List<String> = if (deviceAddress != null) {
            if (characteristic.subscriptionFor(deviceAddress) == SubscriptionState.NONE) {
                throw BleException(ErrorCodes.NOT_SUBSCRIBED, "Device $deviceAddress is not subscribed to $characteristicUuid")
            }
            listOf(deviceAddress)
        } else {
            characteristic.subscribedDevices().toList()
        }

        characteristic.value = value // keep the read cache in sync

        var failure: BleException? = null
        for (address in targets) {
            val device = devices[address] ?: continue
            val indicate = characteristic.subscriptionFor(address) == SubscriptionState.INDICATE
            try {
                sendNotification(srv, device, characteristic, value, indicate)
            } catch (e: BleException) {
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
    }

    @Suppress("DEPRECATION")
    private suspend fun sendNotification(
        srv: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: GattCharacteristic,
        value: ByteArray,
        indicate: Boolean,
    ) = notifyMutex.withLock {
        val mtu = mtus[device.address] ?: DEFAULT_MTU
        if (value.size > mtu - ATT_HEADER_SIZE) {
            throw BleException(
                ErrorCodes.VALUE_TOO_LARGE,
                "Value of ${value.size} bytes exceeds the maximum of ${mtu - ATT_HEADER_SIZE} for ${device.address} (MTU $mtu)",
            )
        }

        val ack = CompletableDeferred<Int>()
        pendingNotification = PendingNotification(device.address, ack)

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            srv.notifyCharacteristicChanged(device, characteristic.native, indicate, value) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.native.value = value
            srv.notifyCharacteristicChanged(device, characteristic.native, indicate)
        }

        if (!accepted) {
            pendingNotification = null
            throw BleException(ErrorCodes.NOTIFY_FAILED, "The Bluetooth stack rejected the notification for ${device.address}")
        }

        val status = try {
            withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { ack.await() }
        } finally {
            pendingNotification = null
        }
        if (status != BLUETOOTH_GATT_SUCCESS) {
            throw BleException(
                ErrorCodes.NOTIFY_FAILED,
                if (status == null) "Timed out waiting for delivery to ${device.address}"
                else "Delivery to ${device.address} failed with status $status",
            )
        }
    }

    // --- Responses from JS --------------------------------------------------------------

    /** Completes a pending read/write request. Late or duplicate responses are ignored. */
    fun respond(requestId: String, status: Int, value: ByteArray?) {
        pendingRequests[requestId]?.deferred?.complete(Response(status, value))
    }

    private suspend fun deliverRead(
        device: BluetoothDevice,
        characteristic: GattCharacteristic,
        serviceUuid: UUID,
    ): Response {
        if (!characteristic.dynamicRead) return Response(AttStatus.SUCCESS, characteristic.value)

        val id = requestCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<Response>()
        pendingRequests[id] = PendingRequest(device.address, deferred)
        emit(PeripheralEvent.ReadRequest(id, device.address, serviceUuid, characteristic.uuid))

        val response = withTimeoutOrNull(requestTimeoutMs) { deferred.await() }
        pendingRequests.remove(id)
        return response ?: Response(AttStatus.UNLIKELY_ERROR, null)
    }

    /** Returns the ATT status to answer the central with. */
    private suspend fun deliverWrite(
        device: BluetoothDevice,
        characteristic: GattCharacteristic,
        serviceUuid: UUID,
        value: ByteArray,
        responseNeeded: Boolean,
    ): Int {
        if (!characteristic.dynamicWrite) {
            characteristic.value = value
            return AttStatus.SUCCESS
        }

        val id = requestCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<Response>()
        pendingRequests[id] = PendingRequest(device.address, deferred)
        emit(PeripheralEvent.WriteRequest(id, device.address, serviceUuid, characteristic.uuid, value, responseNeeded))

        val response = withTimeoutOrNull(requestTimeoutMs) { deferred.await() }
        pendingRequests.remove(id)
        val status = response?.status ?: AttStatus.UNLIKELY_ERROR
        if (status == AttStatus.SUCCESS) characteristic.value = value
        return status
    }

    // --- Android callback ---------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BLUETOOTH_GATT_SUCCESS) {
                        devices[device.address] = device
                        emit(PeripheralEvent.DeviceConnected(device.address))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(device)
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            pendingServiceAdds[service.uuid]?.complete(status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            emit(PeripheralEvent.MtuChanged(device.address, mtu))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotification?.takeIf { it.deviceAddress == device.address }?.ack?.complete(status)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val gc = find(characteristic)
            if (gc == null) {
                reply(device, requestId, AttStatus.INVALID_HANDLE, offset, null)
                return
            }
            if (CharacteristicProperty.READ !in gc.properties) {
                reply(device, requestId, AttStatus.READ_NOT_PERMITTED, offset, null)
                return
            }

            val serviceUuid = characteristic.service.uuid
            val snapshotKey = snapshotKey(device.address, serviceUuid, gc.uuid)

            scope.launch {
                // Follow-up chunks of a long read reuse the value captured at offset 0.
                val snapshot = if (offset > 0) readSnapshots[snapshotKey] else null
                val value: ByteArray
                if (snapshot != null) {
                    value = snapshot
                } else {
                    val response = deliverRead(device, gc, serviceUuid)
                    if (response.status != AttStatus.SUCCESS) {
                        reply(device, requestId, response.status, offset, null)
                        return@launch
                    }
                    value = response.value ?: ByteArray(0)
                    readSnapshots[snapshotKey] = value
                }

                if (offset > value.size) {
                    reply(device, requestId, AttStatus.INVALID_OFFSET, offset, null)
                } else {
                    reply(device, requestId, AttStatus.SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            val gc = find(characteristic)
            if (gc == null) {
                if (responseNeeded) reply(device, requestId, AttStatus.INVALID_HANDLE, offset, null)
                return
            }
            val writable = CharacteristicProperty.WRITE in gc.properties ||
                CharacteristicProperty.WRITE_WITHOUT_RESPONSE in gc.properties
            if (!writable) {
                if (responseNeeded) reply(device, requestId, AttStatus.WRITE_NOT_PERMITTED, offset, null)
                return
            }

            val serviceUuid = characteristic.service.uuid

            if (preparedWrite) {
                // Long write: queue the chunk, echo it back, and deliver once on onExecuteWrite.
                preparedWrites.getOrPut(device.address) { CopyOnWriteArrayList() }
                    .add(PreparedChunk(gc, serviceUuid, offset, bytes))
                reply(device, requestId, AttStatus.SUCCESS, offset, bytes)
                return
            }

            scope.launch {
                val status = deliverWrite(device, gc, serviceUuid, bytes, responseNeeded)
                if (responseNeeded) reply(device, requestId, status, offset, bytes)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val chunks = preparedWrites.remove(device.address)?.toList().orEmpty()
            if (!execute || chunks.isEmpty()) {
                reply(device, requestId, AttStatus.SUCCESS, 0, null)
                return
            }

            scope.launch {
                var status = AttStatus.SUCCESS
                for ((gc, parts) in chunks.groupBy { it.characteristic }) {
                    val size = parts.maxOf { it.offset + it.value.size }
                    val assembled = ByteArray(size)
                    parts.forEach { System.arraycopy(it.value, 0, assembled, it.offset, it.value.size) }

                    val result = deliverWrite(device, gc, parts.first().serviceUuid, assembled, true)
                    if (result != AttStatus.SUCCESS) {
                        status = result
                        break
                    }
                }
                reply(device, requestId, status, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val gc = descriptor.characteristic?.let { find(it) }
            if (gc == null) {
                reply(device, requestId, AttStatus.INVALID_HANDLE, offset, null)
                return
            }
            if (descriptor.uuid != GattCharacteristic.CCCD_UUID) {
                reply(device, requestId, AttStatus.REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            val value = when (gc.subscriptionFor(device.address)) {
                SubscriptionState.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                SubscriptionState.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                SubscriptionState.NONE -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            if (offset > value.size) {
                reply(device, requestId, AttStatus.INVALID_OFFSET, offset, null)
            } else {
                reply(device, requestId, AttStatus.SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val gc = descriptor.characteristic?.let { find(it) }
            if (gc == null) {
                if (responseNeeded) reply(device, requestId, AttStatus.INVALID_HANDLE, offset, null)
                return
            }
            if (descriptor.uuid != GattCharacteristic.CCCD_UUID || preparedWrite) {
                if (responseNeeded) reply(device, requestId, AttStatus.REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            val bytes = value ?: ByteArray(0)
            val requested = when {
                bytes.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> SubscriptionState.NOTIFY
                bytes.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> SubscriptionState.INDICATE
                bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> SubscriptionState.NONE
                else -> {
                    if (responseNeeded) reply(device, requestId, AttStatus.REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
            }

            val supported = when (requested) {
                SubscriptionState.NOTIFY -> CharacteristicProperty.NOTIFY in gc.properties
                SubscriptionState.INDICATE -> CharacteristicProperty.INDICATE in gc.properties
                SubscriptionState.NONE -> true
            }
            if (!supported) {
                if (responseNeeded) reply(device, requestId, AttStatus.REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            val previous = gc.subscriptionFor(device.address)
            gc.onCccdWrite(device.address, bytes)
            if (responseNeeded) reply(device, requestId, AttStatus.SUCCESS, offset, bytes)

            if (previous != requested) {
                val serviceUuid = gc.native.service.uuid
                if (requested == SubscriptionState.NONE) {
                    emit(PeripheralEvent.Unsubscribed(device.address, serviceUuid, gc.uuid))
                } else {
                    val mode = if (requested == SubscriptionState.INDICATE) "indicate" else "notify"
                    emit(PeripheralEvent.Subscribed(device.address, serviceUuid, gc.uuid, mode))
                }
            }
        }
    }

    // --- Helpers ------------------------------------------------------------------------

    private fun handleDisconnect(device: BluetoothDevice) {
        val address = device.address
        devices.remove(address)
        mtus.remove(address)
        preparedWrites.remove(address)
        readSnapshots.keys.removeIf { it.startsWith("$address|") }

        // Unblock anything still waiting on this device.
        pendingRequests.values
            .filter { it.deviceAddress == address }
            .forEach { it.deferred.complete(Response(AttStatus.UNLIKELY_ERROR, null)) }
        pendingNotification?.takeIf { it.deviceAddress == address }?.ack?.complete(BLUETOOTH_GATT_FAILURE)

        // The central is gone, so its subscriptions are too.
        for (gc in characteristicIndex.values) {
            if (gc.subscriptionFor(address) != SubscriptionState.NONE) {
                gc.onDeviceDisconnected(address)
                emit(PeripheralEvent.Unsubscribed(address, gc.native.service.uuid, gc.uuid))
            }
        }

        emit(PeripheralEvent.DeviceDisconnected(address))
    }

    private fun failEverythingPending() {
        pendingRequests.values.forEach { it.deferred.complete(Response(AttStatus.UNLIKELY_ERROR, null)) }
        pendingServiceAdds.values.forEach { it.complete(BLUETOOTH_GATT_FAILURE) }
        pendingNotification?.ack?.complete(BLUETOOTH_GATT_FAILURE)
    }

    private fun find(native: BluetoothGattCharacteristic): GattCharacteristic? =
        characteristicIndex[indexKey(native.service.uuid, native.uuid)]

    private fun reply(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (e: Exception) {
            Log.w(TAG, "sendResponse failed for ${device.address}", e)
        }
    }

    private fun indexKey(serviceUuid: UUID, characteristicUuid: UUID) = "$serviceUuid/$characteristicUuid"

    private fun snapshotKey(address: String, serviceUuid: UUID, characteristicUuid: UUID) =
        "$address|$serviceUuid|$characteristicUuid"

    private companion object {
        const val TAG = "RNBlePeripheral"
        const val BLUETOOTH_GATT_SUCCESS = 0
        const val BLUETOOTH_GATT_FAILURE = 257
        const val DEFAULT_MTU = 23
        const val ATT_HEADER_SIZE = 3
        const val SERVICE_ADD_TIMEOUT_MS = 5_000L
        const val NOTIFY_TIMEOUT_MS = 5_000L
    }
}
