package com.reactnative.bleperipheral.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.reactnative.bleperipheral.BleException
import com.reactnative.bleperipheral.ErrorCodes
import com.reactnative.bleperipheral.PeripheralEvent
import com.reactnative.bleperipheral.gatt.GattServer
import com.reactnative.bleperipheral.gatt.GattService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Core wrapper around Android's BLE peripheral APIs: owns the [GattServer], the advertiser and
 * Bluetooth state tracking. Lifecycle is explicit: nothing happens until [start] is called.
 *
 * One-shot operations are suspend functions; everything that streams (requests, connection
 * changes, Bluetooth state) is exposed through [events].
 */
@SuppressLint("MissingPermission")
class BlePeripheral(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    @Volatile private var gattServer: GattServer? = null
    @Volatile private var advertiseCallback: AdvertiseCallback? = null
    @Volatile private var originalAdapterName: String? = null

    // Buffered so GATT binder threads never block; large enough that request events aren't dropped.
    private val _events = MutableSharedFlow<PeripheralEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PeripheralEvent> = _events.asSharedFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = currentState()
            // Like iOS: when Bluetooth goes away the GATT database is gone. JS must start() and re-add services.
            if (state != STATE_POWERED_ON) stop()
            _events.tryEmit(PeripheralEvent.StateChanged(state))
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, filter)
        }
    }

    // --- Lifecycle ----------------------------------------------------------------------

    /** Opens the GATT server. Idempotent. */
    fun start() {
        if (gattServer != null) return
        val manager = bluetoothManager
        val adapter = adapter
        if (manager == null || adapter == null ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        ) {
            throw BleException(ErrorCodes.UNSUPPORTED, "This device does not support Bluetooth Low Energy")
        }
        requirePermissions(advertising = false)
        if (!adapter.isEnabled) {
            throw BleException(ErrorCodes.BLUETOOTH_OFF, "Bluetooth is turned off")
        }

        val server = GattServer(
            context = context,
            bluetoothManager = manager,
            scope = scope,
            emit = { event -> _events.tryEmit(event) },
        )
        server.open()
        gattServer = server
    }

    /** Stops advertising and closes the GATT server, disconnecting all centrals. Idempotent. */
    fun stop() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
    }

    /** Unregisters the state receiver. Call when the owning module is destroyed. */
    fun release() {
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        stop()
    }

    private fun requireServer(): GattServer =
        gattServer ?: throw BleException(ErrorCodes.NOT_STARTED, "Peripheral is not started. Call start() first.")

    // --- Services & notifications -------------------------------------------------------

    suspend fun addService(service: GattService) = requireServer().addService(service)

    fun removeService(uuid: UUID) = requireServer().removeService(uuid)

    fun removeAllServices() = requireServer().removeAllServices()

    suspend fun notify(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray, deviceId: String?) =
        requireServer().notify(serviceUuid, characteristicUuid, value, deviceId)

    /** Answers a ReadRequest/WriteRequest event. */
    fun respond(requestId: String, status: Int, value: ByteArray?) {
        gattServer?.respond(requestId, status, value)
    }

    fun connectedDevices(): List<String> = gattServer?.connectedDeviceIds().orEmpty()

    // --- Advertising --------------------------------------------------------------------

    val isAdvertising: Boolean
        get() = advertiseCallback != null

    /**
     * Starts a connectable advertisement carrying [serviceUuids].
     *
     * Android has no per-advertisement local name: when [name] is given the adapter's Bluetooth
     * name is changed (and restored by [stopAdvertising]) and sent in the scan response, which
     * leaves the 31-byte advertisement itself free for UUIDs.
     */
    suspend fun startAdvertising(name: String?, serviceUuids: List<UUID>) {
        requireServer()
        requirePermissions(advertising = true)
        val adapter = adapter ?: throw BleException(ErrorCodes.UNSUPPORTED, "Bluetooth is not available")
        if (!adapter.isEnabled) throw BleException(ErrorCodes.BLUETOOTH_OFF, "Bluetooth is turned off")
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw BleException(ErrorCodes.UNSUPPORTED, "BLE advertising is not supported on this device")
        if (advertiseCallback != null) {
            throw BleException(ErrorCodes.ADVERTISE_FAILED, "Already advertising")
        }

        if (name != null && name != adapter.name) {
            if (originalAdapterName == null) originalAdapterName = adapter.name
            adapter.setName(name)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .apply { serviceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
            .build()

        val scanResponse = if (name != null) {
            AdvertiseData.Builder().setIncludeDeviceName(true).build()
        } else {
            null
        }

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        advertiseCallback = this
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onStartFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                BleException(ErrorCodes.ADVERTISE_FAILED, describeAdvertiseError(errorCode)),
                            )
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    try {
                        advertiser.stopAdvertising(callback)
                    } catch (e: Exception) {
                        Log.w(TAG, "stopAdvertising after cancellation failed", e)
                    }
                }
                if (scanResponse != null) {
                    advertiser.startAdvertising(settings, data, scanResponse, callback)
                } else {
                    advertiser.startAdvertising(settings, data, callback)
                }
            }
        } catch (e: Exception) {
            restoreAdapterName()
            throw e
        }
    }

    /** Idempotent. */
    fun stopAdvertising() {
        val callback = advertiseCallback
        advertiseCallback = null
        if (callback != null) {
            try {
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            } catch (e: Exception) {
                Log.w(TAG, "stopAdvertising failed", e)
            }
        }
        restoreAdapterName()
    }

    private fun restoreAdapterName() {
        val original = originalAdapterName ?: return
        originalAdapterName = null
        try {
            adapter?.setName(original)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not restore the Bluetooth name", e)
        }
    }

    // --- State & permissions ------------------------------------------------------------

    /** One of: poweredOn, poweredOff, resetting, unauthorized, unsupported, unknown. */
    fun currentState(): String {
        val adapter = adapter ?: return STATE_UNSUPPORTED
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return STATE_UNSUPPORTED
        if (missingPermissions(advertising = false).isNotEmpty()) return STATE_UNAUTHORIZED
        return when (adapter.state) {
            BluetoothAdapter.STATE_ON -> STATE_POWERED_ON
            BluetoothAdapter.STATE_OFF -> STATE_POWERED_OFF
            BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> STATE_RESETTING
            else -> STATE_UNKNOWN
        }
    }

    /** Runtime permissions that are required but not granted (only relevant on Android 12+). */
    fun missingPermissions(advertising: Boolean): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val required = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (advertising) add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requirePermissions(advertising: Boolean) {
        val missing = missingPermissions(advertising)
        if (missing.isNotEmpty()) {
            throw BleException(
                ErrorCodes.PERMISSION,
                "Missing runtime permission(s): ${missing.joinToString()}. Request them before using the peripheral.",
            )
        }
    }

    private fun describeAdvertiseError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Advertisement data is too large (31-byte limit). Use fewer or shorter service UUIDs."
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers are active"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising is not supported"
        else -> "Advertising failed with internal error $code"
    }

    private companion object {
        const val TAG = "RNBlePeripheral"
        const val STATE_POWERED_ON = "poweredOn"
        const val STATE_POWERED_OFF = "poweredOff"
        const val STATE_RESETTING = "resetting"
        const val STATE_UNAUTHORIZED = "unauthorized"
        const val STATE_UNSUPPORTED = "unsupported"
        const val STATE_UNKNOWN = "unknown"
    }
}
