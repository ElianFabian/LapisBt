package com.elianfabian.lapisbt_rpc.util

import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.lang.reflect.Array
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.Enumeration
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException
import kotlin.math.min

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


internal fun Type.getRawClass(): Class<*> {
	val type = this

	if (type is Class<*>) {
		// Type is a normal class.
		return type
	}
	if (type is ParameterizedType) {
		// I'm not exactly sure why getRawType() returns Type instead of Class. Neal isn't either but
		// suspects some pathological case related to nested classes exists.
		val rawType = type.rawType
		require(rawType is Class<*>)
		return rawType
	}
	if (type is GenericArrayType) {
		val componentType = type.genericComponentType
		return Array.newInstance(componentType.getRawClass(), 0).javaClass
	}
	if (type is TypeVariable<*>) {
		// We could use the variable's bounds, but that won't work if there are multiple. Having a raw
		// type that's more general than necessary is okay.
		return Any::class.java
	}
	if (type is WildcardType) {
		return type.upperBounds[0].getRawClass()
	}

	throw IllegalArgumentException(
		"Expected a Class, ParameterizedType, or GenericArrayType, but <$type> is of type ${type.javaClass.getName()}"
	)
}


// From: https://stackoverflow.com/a/47683449/18418162
internal suspend fun Method.invokeSuspend(obj: Any, vararg args: Any?): Any? {
	return suspendCoroutineUninterceptedOrReturn { cont ->
		invoke(obj, *args, cont)
	}
}

internal fun Method.isSuspend(): Boolean {
	if (parameterTypes.isEmpty()) {
		return false
	}

	return Continuation::class.java.isAssignableFrom(parameterTypes.last())
}

internal fun Type.extractFirstGenericArgument(): Class<*> {
	return when (this) {
		is ParameterizedType -> {
			when (val actualType = actualTypeArguments.first()) {
				is Class<*> -> actualType
				is ParameterizedType -> actualType.rawType as? Class<*> ?: error("Cannot determine return type of suspend function $this")
				is WildcardType -> actualType.lowerBounds.firstOrNull() as? Class<*> ?: actualType.upperBounds.firstOrNull() as? Class<*> ?: error("Cannot determine return type of suspend function $this")
				else -> Any::class.java
			}
		}
		else -> error("Unsupported return type: $this")
	}
}

internal fun Method.getSuspendReturnType(): Class<*> {
	check(isSuspend()) {
		"Method $this is not a suspend function"
	}
	return genericParameterTypes.last().extractFirstGenericArgument()
}

internal fun Method.getFlowReturnType(): Class<*> {
	return genericReturnType.extractFirstGenericArgument()
}

internal fun ByteArray.padded(
	targetSize: Int,
): ByteArray {
	if (this.size >= targetSize) {
		return this
	}
	val paddedArray = ByteArray(targetSize)
	this.copyInto(paddedArray)
	return paddedArray
}
