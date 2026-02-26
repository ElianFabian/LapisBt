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

// We should allow users to provide their own serialization strategy, but for development purposes, we can use this default strategy that provides serializers for the most common types.
internal object DefaultSerializationStrategy : SerializationStrategy {
	override fun serializerForClass(type: KClass<*>): LapisSerializer<*>? {
		return when (type) {
			Unit::class -> UnitSerializer
			Nothing::class -> NullSerializer
			Boolean::class -> BooleanSerializer
			Byte::class -> ByteSerializer
			Int::class -> IntSerializer
			Short::class -> ShortSerializer
			Long::class -> LongSerializer
			Float::class -> FloatSerializer
			Double::class -> DoubleSerializer
			Char::class -> CharSerializer
			String::class -> StringSerializer
			BooleanArray::class -> BooleanArraySerializer
			ByteArray::class -> ByteArraySerializer
			ShortArray::class -> ShortArraySerializer
			IntArray::class -> IntArraySerializer
			LongArray::class -> LongArraySerializer
			FloatArray::class -> FloatArraySerializer
			DoubleArray::class -> DoubleArraySerializer
			CharArray::class -> CharArraySerializer

			UByte::class -> UByteSerializer
			UShort::class -> UShortSerializer
			UInt::class -> UIntSerializer
			ULong::class -> ULongSerializer

			@OptIn(ExperimentalUnsignedTypes::class)
			UByteArray::class,
				-> UByteArraySerializer
			@OptIn(ExperimentalUnsignedTypes::class)
			UShortArray::class,
				-> UShortArraySerializer
			@OptIn(ExperimentalUnsignedTypes::class)
			UIntArray::class,
				-> UIntArraySerializer
			@OptIn(ExperimentalUnsignedTypes::class)
			ULongArray::class,
				-> ULongArraySerializer
			else -> null
		}
	}
}
