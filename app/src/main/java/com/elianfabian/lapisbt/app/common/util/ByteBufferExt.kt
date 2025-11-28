package com.elianfabian.lapisbt.app.common.util

import java.nio.ByteBuffer

fun ByteBuffer.putString(str: String) {
    val bytes = str.toByteArray(Charsets.UTF_8)
    putInt(bytes.size)
    put(bytes)
}

fun ByteBuffer.getString(): String {
    val length = getInt()
    val bytes = ByteArray(length)
    get(bytes)
    return String(bytes, Charsets.UTF_8)
}
