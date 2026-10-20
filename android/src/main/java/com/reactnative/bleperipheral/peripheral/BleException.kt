package com.reactnative.bleperipheral

/** Error with a stable, machine-readable [code] that the bridge forwards to JS promise rejections. */
class BleException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

object ErrorCodes {
    const val PERMISSION = "E_PERMISSION"
    const val BLUETOOTH_OFF = "E_BLUETOOTH_OFF"
    const val UNSUPPORTED = "E_UNSUPPORTED"
    const val NOT_STARTED = "E_NOT_STARTED"
    const val OPEN_FAILED = "E_OPEN_FAILED"
    const val ADVERTISE_FAILED = "E_ADVERTISE_FAILED"
    const val SERVICE_FAILED = "E_SERVICE_FAILED"
    const val SERVICE_EXISTS = "E_SERVICE_EXISTS"
    const val NOT_FOUND = "E_NOT_FOUND"
    const val NOT_SUBSCRIBED = "E_NOT_SUBSCRIBED"
    const val VALUE_TOO_LARGE = "E_VALUE_TOO_LARGE"
    const val NOTIFY_FAILED = "E_NOTIFY_FAILED"
    const val INVALID_ARGUMENT = "E_INVALID_ARGUMENT"
    const val INTERNAL = "E_INTERNAL"
}
