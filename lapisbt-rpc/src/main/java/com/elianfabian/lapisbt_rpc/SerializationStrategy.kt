package com.elianfabian.lapisbt_rpc

import kotlin.reflect.KClass

// With this strategy, custom serializers can be provided for specific types.
// For example, if we want to implement JSON serialization, we can create a strategy that returns JSON serializers for certain classes
// that have specific annotations or implement specific interfaces.
internal interface SerializationStrategy {

	/**
	 * Returns the serializer for the given class, or null to use the default serialization.
	 */
	fun serializerForClass(type: KClass<*>): LapisSerializer<*>?
}

internal object DefaultSerializationStrategy : SerializationStrategy {
	override fun serializerForClass(type: KClass<*>): LapisSerializer<*>? {
		return when (type) {
			Int::class -> IntSerializer
			Long::class -> LongSerializer
			Short::class -> ShortSerializer
			Byte::class -> ByteSerializer
			Float::class -> FloatSerializer
			Double::class -> DoubleSerializer
			Boolean::class -> BooleanSerializer
			Char::class -> CharSerializer
			String::class -> StringSerializer
			ByteArray::class -> ByteArraySerializer
			else -> null
		}
	}
}
