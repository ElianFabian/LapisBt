package com.elianfabian.lapisbt_rpc

import kotlin.reflect.KClass

// With this strategy, custom serializers can be provided for specific types.
// For example, if we want to implement JSON serialization, we can create a strategy that returns JSON serializers for certain classes
// that have specific annotations or implement specific interfaces.
public interface LapisSerializationStrategy {

	/**
	 * Returns the serializer for the given class, or null to use the default serialization.
	 */
	public fun serializerForClass(type: KClass<*>): LapisSerializer<*>?
}


internal fun LapisSerializationStrategy.withDefaultFallback(): LapisSerializationStrategy {
	val original = this
	return object : LapisSerializationStrategy {
		override fun serializerForClass(type: KClass<*>): LapisSerializer<*>? {
			if (original != DefaultSerializationStrategy) {
				return original.serializerForClass(type) ?: DefaultSerializationStrategy.serializerForClass(type)
			}
			return original.serializerForClass(type)
		}
	}
}


internal object DefaultSerializationStrategy : LapisSerializationStrategy {
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
