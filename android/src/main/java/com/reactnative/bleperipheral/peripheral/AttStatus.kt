package com.reactnative.bleperipheral.gatt

/**
 * ATT error codes usable in `sendResponse`. Android exposes only some of these as public
 * constants, so the Bluetooth Core Spec values are used directly.
 */
object AttStatus {
    const val SUCCESS = 0x00
    const val INVALID_HANDLE = 0x01
    const val READ_NOT_PERMITTED = 0x02
    const val WRITE_NOT_PERMITTED = 0x03
    const val INSUFFICIENT_AUTHENTICATION = 0x05
    const val REQUEST_NOT_SUPPORTED = 0x06
    const val INVALID_OFFSET = 0x07
    const val INSUFFICIENT_AUTHORIZATION = 0x08
    const val INVALID_ATTRIBUTE_LENGTH = 0x0D
    const val UNLIKELY_ERROR = 0x0E
    const val INSUFFICIENT_ENCRYPTION = 0x0F

    /** Maps the JS `AttStatus` strings. Throws IllegalArgumentException for unknown names. */
    fun fromJs(name: String): Int = when (name) {
        "success" -> SUCCESS
        "invalidHandle" -> INVALID_HANDLE
        "readNotPermitted" -> READ_NOT_PERMITTED
        "writeNotPermitted" -> WRITE_NOT_PERMITTED
        "insufficientAuthentication" -> INSUFFICIENT_AUTHENTICATION
        "requestNotSupported" -> REQUEST_NOT_SUPPORTED
        "invalidOffset" -> INVALID_OFFSET
        "insufficientAuthorization" -> INSUFFICIENT_AUTHORIZATION
        "invalidAttributeLength" -> INVALID_ATTRIBUTE_LENGTH
        "unlikelyError" -> UNLIKELY_ERROR
        "insufficientEncryption" -> INSUFFICIENT_ENCRYPTION
        else -> throw IllegalArgumentException("Unknown ATT status: $name")
    }
}
