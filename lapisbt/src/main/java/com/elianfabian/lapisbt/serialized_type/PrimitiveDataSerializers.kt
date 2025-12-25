package com.elianfabian.lapisbt.serialized_type

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object UnitSerializer : LapisDataSerializer<Unit> {

	override val type = Unit::class

	override fun serialize(stream: OutputStream, data: Unit) {
		// no-op
	}

	override fun deserialize(stream: InputStream) {
		// no-op
	}
}

internal object BooleanSerializer : LapisDataSerializer<Boolean> {

	override val type = Boolean::class

	override fun serialize(stream: OutputStream, data: Boolean) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeBoolean(data)
	}

	override fun deserialize(stream: InputStream): Boolean {
		val dataStream = DataInputStream(stream)
		return dataStream.readBoolean()
	}
}

internal object BooleanArraySerializer : LapisDataSerializer<BooleanArray> {

	override val type = BooleanArray::class

	override fun serialize(stream: OutputStream, data: BooleanArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)

		var currentByte = 0
		var bitIndex = 0

		for (value in data) {
			if (value) {
				currentByte = currentByte or (1 shl (Byte.SIZE_BITS - 1 - bitIndex))
			}
			bitIndex++

			if (bitIndex == Byte.SIZE_BITS) {
				dataStream.writeByte(currentByte)
				currentByte = 0
				bitIndex = 0
			}
		}

		if (bitIndex != 0) {
			dataStream.writeByte(currentByte)
		}
	}

	override fun deserialize(stream: InputStream): BooleanArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid BooleanArray size: $size"
		}

		val result = BooleanArray(size)
		var currentByte = 0

		for (i in 0 until size) {
			if (i % Byte.SIZE_BITS == 0) {
				currentByte = dataStream.readByte().toInt()
			}
			val bitIndex = i % Byte.SIZE_BITS
			result[i] = (currentByte and (1 shl (Byte.SIZE_BITS - 1 - bitIndex))) != 0
		}

		return result
	}
}

internal object CharSerializer : LapisDataSerializer<Char> {

	override val type = Char::class

	override fun serialize(stream: OutputStream, data: Char) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeChar(data.code)
	}

	override fun deserialize(stream: InputStream): Char {
		val dataStream = DataInputStream(stream)
		return dataStream.readChar()
	}
}

internal object CharArraySerializer : LapisDataSerializer<CharArray> {

	override val type = CharArray::class

	override fun serialize(stream: OutputStream, data: CharArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (char in data) {
			dataStream.writeChar(char.code)
		}
	}

	override fun deserialize(stream: InputStream): CharArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid CharArray size: $size"
		}

		val result = CharArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readChar()
		}
		return result
	}
}

internal object StringSerializer : LapisDataSerializer<String> {

	override val type = String::class

	override fun serialize(stream: OutputStream, data: String) {
		val dataStream = DataOutputStream(stream)
		val stringBytes = data.toByteArray(Charsets.UTF_8)
		dataStream.writeInt(stringBytes.size)
		dataStream.write(stringBytes)
	}

	override fun deserialize(stream: InputStream): String {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid String size: $size"
		}

		val stringBytes = ByteArray(size)
		dataStream.readFully(stringBytes)
		return String(stringBytes, Charsets.UTF_8)
	}
}

internal object ByteSerializer : LapisDataSerializer<Byte> {

	override val type = Byte::class

	override fun serialize(stream: OutputStream, data: Byte) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeByte(data.toInt())
	}

	override fun deserialize(stream: InputStream): Byte {
		val dataStream = DataInputStream(stream)
		return dataStream.readByte()
	}
}

internal object ByteArraySerializer : LapisDataSerializer<ByteArray> {

	override val type = ByteArray::class

	override fun serialize(stream: OutputStream, data: ByteArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)

		dataStream.write(data)
	}

	override fun deserialize(stream: InputStream): ByteArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid String size: $size"
		}

		val result = ByteArray(size)
		dataStream.readFully(result)
		return result
	}
}

internal object ShortSerializer : LapisDataSerializer<Short> {

	override val type = Short::class

	override fun serialize(stream: OutputStream, data: Short) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeShort(data.toInt())
	}

	override fun deserialize(stream: InputStream): Short {
		val dataStream = DataInputStream(stream)
		return dataStream.readShort()
	}
}

internal object ShortArraySerializer : LapisDataSerializer<ShortArray> {

	override val type = ShortArray::class

	override fun serialize(stream: OutputStream, data: ShortArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)

		for (shortValue in data) {
			dataStream.writeShort(shortValue.toInt())
		}
	}

	override fun deserialize(stream: InputStream): ShortArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid ShortArray size: $size"
		}

		val result = ShortArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readShort()
		}
		return result
	}
}

internal object IntSerializer : LapisDataSerializer<Int> {

	override val type = Int::class

	override fun serialize(stream: OutputStream, data: Int) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data)
	}

	override fun deserialize(stream: InputStream): Int {
		val dataStream = DataInputStream(stream)
		return dataStream.readInt()
	}
}

internal object IntArraySerializer : LapisDataSerializer<IntArray> {

	override val type = IntArray::class

	override fun serialize(stream: OutputStream, data: IntArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (intValue in data) {
			dataStream.writeInt(intValue)
		}
	}

	override fun deserialize(stream: InputStream): IntArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid IntArray size: $size"
		}

		val result = IntArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readInt()
		}
		return result
	}
}

internal object LongSerializer : LapisDataSerializer<Long> {

	override val type = Long::class

	override fun serialize(stream: OutputStream, data: Long) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data)
	}

	override fun deserialize(stream: InputStream): Long {
		val dataStream = DataInputStream(stream)
		return dataStream.readLong()
	}
}

internal object LongArraySerializer : LapisDataSerializer<LongArray> {

	override val type = LongArray::class

	override fun serialize(stream: OutputStream, data: LongArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (longValue in data) {
			dataStream.writeLong(longValue)
		}
	}

	override fun deserialize(stream: InputStream): LongArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid LongArray size: $size"
		}

		val result = LongArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readLong()
		}
		return result
	}
}

internal object FloatSerializer : LapisDataSerializer<Float> {

	override val type = Float::class

	override fun serialize(stream: OutputStream, data: Float) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeFloat(data)
	}

	override fun deserialize(stream: InputStream): Float {
		val dataStream = DataInputStream(stream)
		return dataStream.readFloat()
	}
}

internal object FloatArraySerializer : LapisDataSerializer<FloatArray> {

	override val type = FloatArray::class

	override fun serialize(stream: OutputStream, data: FloatArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (floatValue in data) {
			dataStream.writeFloat(floatValue)
		}
	}

	override fun deserialize(stream: InputStream): FloatArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid FloatArray size: $size"
		}

		val result = FloatArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readFloat()
		}
		return result
	}
}

internal object DoubleSerializer : LapisDataSerializer<Double> {

	override val type = Double::class

	override fun serialize(stream: OutputStream, data: Double) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeDouble(data)
	}

	override fun deserialize(stream: InputStream): Double {
		val dataStream = DataInputStream(stream)
		return dataStream.readDouble()
	}
}

internal object DoubleArraySerializer : LapisDataSerializer<DoubleArray> {

	override val type = DoubleArray::class

	override fun serialize(stream: OutputStream, data: DoubleArray) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.size)
		for (doubleValue in data) {
			dataStream.writeDouble(doubleValue)
		}
	}

	override fun deserialize(stream: InputStream): DoubleArray {
		val dataStream = DataInputStream(stream)
		val size = dataStream.readInt()
		require(size >= 0) {
			"Invalid DoubleArray size: $size"
		}

		val result = DoubleArray(size)
		for (i in 0 until size) {
			result[i] = dataStream.readDouble()
		}
		return result
	}
}

internal object UByteSerializer : LapisDataSerializer<UByte> {

	override val type = UByte::class

	override fun serialize(stream: OutputStream, data: UByte) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeByte(data.toInt())
	}

	override fun deserialize(stream: InputStream): UByte {
		val dataStream = DataInputStream(stream)
		return dataStream.readUnsignedByte().toUByte()
	}
}

@OptIn(ExperimentalUnsignedTypes::class)
internal object UByteArraySerializer : LapisDataSerializer<UByteArray> {

	override val type = UByteArray::class

	override fun serialize(stream: OutputStream, data: UByteArray) {
		ByteArraySerializer.serialize(stream, data.toByteArray())
	}

	override fun deserialize(stream: InputStream): UByteArray {
		val byteArray = ByteArraySerializer.deserialize(stream)
		return UByteArray(byteArray.size) { index -> byteArray[index].toUByte() }
	}
}

internal object UShortSerializer : LapisDataSerializer<UShort> {

	override val type = UShort::class

	override fun serialize(stream: OutputStream, data: UShort) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeShort(data.toInt())
	}

	override fun deserialize(stream: InputStream): UShort {
		val dataStream = DataInputStream(stream)
		return dataStream.readUnsignedShort().toUShort()
	}
}

@OptIn(ExperimentalUnsignedTypes::class)
internal object UShortArraySerializer : LapisDataSerializer<UShortArray> {
	override val type = UShortArray::class

	override fun serialize(stream: OutputStream, data: UShortArray) {
		ShortArraySerializer.serialize(
			stream,
			ShortArray(data.size) { index -> data[index].toShort() },
		)
	}

	override fun deserialize(stream: InputStream): UShortArray {
		val shortArray = ShortArraySerializer.deserialize(stream)
		return UShortArray(shortArray.size) { index -> shortArray[index].toUShort() }
	}
}

internal object UIntSerializer : LapisDataSerializer<UInt> {

	override val type = UInt::class

	override fun serialize(stream: OutputStream, data: UInt) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeInt(data.toInt())
	}

	override fun deserialize(stream: InputStream): UInt {
		val dataStream = DataInputStream(stream)
		return dataStream.readInt().toUInt()
	}
}

@OptIn(ExperimentalUnsignedTypes::class)
internal object UIntArraySerializer : LapisDataSerializer<UIntArray> {
	override val type = UIntArray::class

	override fun serialize(stream: OutputStream, data: UIntArray) {
		IntArraySerializer.serialize(
			stream,
			IntArray(data.size) { index -> data[index].toInt() },
		)
	}

	override fun deserialize(stream: InputStream): UIntArray {
		val intArray = IntArraySerializer.deserialize(stream)
		return UIntArray(intArray.size) { index -> intArray[index].toUInt() }
	}
}

internal object ULongSerializer : LapisDataSerializer<ULong> {

	override val type = ULong::class

	override fun serialize(stream: OutputStream, data: ULong) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data.toLong())
	}

	override fun deserialize(stream: InputStream): ULong {
		val dataStream = DataInputStream(stream)
		return dataStream.readLong().toULong()
	}
}

@OptIn(ExperimentalUnsignedTypes::class)
internal object ULongArraySerializer : LapisDataSerializer<ULongArray> {

	override val type = ULongArray::class

	override fun serialize(stream: OutputStream, data: ULongArray) {
		LongArraySerializer.serialize(
			stream,
			LongArray(data.size) { index -> data[index].toLong() },
		)
	}

	override fun deserialize(stream: InputStream): ULongArray {
		val longArray = LongArraySerializer.deserialize(stream)
		return ULongArray(longArray.size) { index -> longArray[index].toULong() }
	}
}
