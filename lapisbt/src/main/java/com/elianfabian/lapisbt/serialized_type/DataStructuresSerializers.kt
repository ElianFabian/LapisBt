package com.elianfabian.lapisbt.serialized_type

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

internal class ArraySerializer<T : Any>(
	private val elementSerializer: LapisDataSerializer<T>,
) : LapisDataSerializer<Array<T>> {

	override val type: KClass<out Array<*>> = Array<Any>::class

	override fun serialize(stream: OutputStream, data: Array<T>) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (element in data) {
			elementSerializer.serialize(stream, element)
		}
	}

	override fun deserialize(stream: InputStream): Array<T> {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid array size: $size"
		}

		@Suppress("UNCHECKED_CAST")
		val array = newArray(
			size = size,
			kClass = elementSerializer.type,
		) as Array<T>

		repeat(size) { index ->
			val element = elementSerializer.deserialize(stream)
			array[index] = element
		}
		return array
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T : Any> newArray(size: Int, kClass: KClass<T>): Array<T> {
		val clazz = when (kClass) {
			Int::class -> Integer::class.java
			Long::class -> java.lang.Long::class.java
			Byte::class -> java.lang.Byte::class.java
			Double::class -> java.lang.Double::class.java
			Float::class -> java.lang.Float::class.java
			Short::class -> java.lang.Short::class.java
			Boolean::class -> java.lang.Boolean::class.java
			Char::class -> Character::class.java
			else -> kClass.java
		}
		return java.lang.reflect.Array.newInstance(clazz, size) as Array<T>
	}
}

internal class CollectionSerializer<T : Any>(
	private val elementSerializer: LapisDataSerializer<T>,
) : LapisDataSerializer<Collection<T>> {

	override val type: KClass<out Collection<*>> = Collection::class


	override fun serialize(stream: OutputStream, data: Collection<T>) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (element in data) {
			elementSerializer.serialize(stream, element)
		}
	}

	override fun deserialize(stream: InputStream): Collection<T> {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid collection size: $size"
		}

		val list = mutableListOf<T>()
		repeat(size) {
			val element = elementSerializer.deserialize(stream)
			list.add(element)
		}
		return list
	}
}

internal class MapSerializer<K : Any, V : Any>(
	private val keySerializer: LapisDataSerializer<K>,
	private val valueSerializer: LapisDataSerializer<V>,
) : LapisDataSerializer<Map<K, V>> {

	override val type: KClass<out Map<*, *>> = Map::class

	override fun serialize(stream: OutputStream, data: Map<K, V>) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		require(data.size >= 0) {
			"Invalid map size: ${data.size}"
		}

		for ((key, value) in data) {
			keySerializer.serialize(stream, key)
			valueSerializer.serialize(stream, value)
		}
	}

	override fun deserialize(stream: InputStream): Map<K, V> {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		val map = mutableMapOf<K, V>()
		repeat(size) {
			val key = keySerializer.deserialize(stream)
			val value = valueSerializer.deserialize(stream)
			map[key] = value
		}
		return map
	}
}
