package com.reactnative.bleperipheral.gatt

import java.util.UUID

object BleUuid {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Accepts 16-bit ("180d"), 32-bit and full 128-bit UUID strings. Throws IllegalArgumentException. */
    fun parse(input: String): UUID {
        val s = input.trim().lowercase()
        return when (s.length) {
            4 -> UUID.fromString("0000$s$BASE_SUFFIX")
            8 -> UUID.fromString("$s$BASE_SUFFIX")
            else -> UUID.fromString(s)
        }
    }
}
