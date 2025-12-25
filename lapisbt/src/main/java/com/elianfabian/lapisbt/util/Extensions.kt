package com.elianfabian.lapisbt.util

import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.util.Enumeration
import kotlin.math.min
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException

internal fun InputStream.readNBytesCompat(len: Int): ByteArray {
	require(len >= 0) { "len < 0" }

	var bufs: MutableList<ByteArray>? = null
	var result: ByteArray? = null
	var total = 0
	var remaining = len
	var n: Int
	do {
		var buf = ByteArray(min(remaining, /* InputStream.DEFAULT_BUFFER_SIZE */ 8192))
		var nread = 0

		// read to EOF which may read more or less than buffer size
		while ((read(
				buf, nread,
				min(buf.size - nread, remaining)
			).also { n = it }) > 0
		) {
			nread += n
			remaining -= n
		}

		if (nread > 0) {
			if (/* MAX_BUFFER_SIZE */ (Int.MAX_VALUE - 8) - total < nread) {
				throw OutOfMemoryError("Required array size too large")
			}
			if (nread < buf.size) {
				buf = buf.copyOfRange(0, nread)
			}
			total += nread
			if (result == null) {
				result = buf
			}
			else {
				if (bufs == null) {
					bufs = ArrayList()
					bufs.add(result)
				}
				bufs.add(buf)
			}
		}
		// if the last call to read returned -1 or the number of bytes
		// requested have been read then break
	}
	while (n >= 0 && remaining > 0)

	if (bufs == null) {
		if (result == null) {
			return ByteArray(0)
		}
		return if (result.size == total) result else result.copyOf(total)
	}

	result = ByteArray(total)
	var offset = 0
	remaining = total
	for (b in bufs) {
		val count = min(b.size, remaining)
		System.arraycopy(b, 0, result, offset, count)
		offset += count
		remaining -= count
	}

	return result
}


/**
 * Source: Retrofit
 *
 * Force the calling coroutine to suspend before throwing [this].
 *
 * This is needed when a checked exception is synchronously caught in a [java.lang.reflect.Proxy]
 * invocation to avoid being wrapped in [java.lang.reflect.UndeclaredThrowableException].
 *
 * The implementation is derived from:
 * https://github.com/Kotlin/kotlinx.coroutines/pull/1667#issuecomment-556106349
 */
internal suspend fun Throwable.suspendAndThrow(): Nothing {
	suspendCoroutineUninterceptedOrReturn<Nothing> { continuation ->
		Dispatchers.Default.dispatch(continuation.context) {
			continuation.intercepted().resumeWithException(this@suspendAndThrow)
		}
		COROUTINE_SUSPENDED
	}
}

internal fun <T> Iterator<T>.asEnumeration(): Enumeration<T> = object : Enumeration<T> {
	override fun hasMoreElements(): Boolean {
		return this@asEnumeration.hasNext()
	}

	override fun nextElement(): T {
		return this@asEnumeration.next()
	}
}
