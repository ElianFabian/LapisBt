package com.elianfabian.lapisbt_rpc.serializer

import java.io.InputStream
import java.io.OutputStream

/**
 * A serializer for a specific type [T].
 */
public interface LapisSerializer<T> {

	/**
	 * Serializes [data] into the provided [OutputStream].
	 */
	public fun serialize(stream: OutputStream, data: T)

	/**
	 * Deserializes an instance of [T] from the provided [InputStream].
	 */
	public fun deserialize(stream: InputStream): T
}
