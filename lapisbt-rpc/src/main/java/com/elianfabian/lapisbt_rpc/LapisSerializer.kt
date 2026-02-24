package com.elianfabian.lapisbt_rpc

import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type

public interface LapisSerializer<T> {

	public fun serialize(stream: OutputStream, data: T)

	public fun deserialize(stream: InputStream): T


	public interface Factory {

		public fun create(type: Type?): LapisSerializer<*>?
	}
}
