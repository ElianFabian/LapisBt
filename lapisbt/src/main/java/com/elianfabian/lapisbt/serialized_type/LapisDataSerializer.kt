package com.elianfabian.lapisbt.serialized_type

import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

internal interface LapisDataSerializer<T : Any> {

	val type: KClass<*>

	fun serialize(stream: OutputStream, data: T)

	fun deserialize(stream: InputStream): T
}
