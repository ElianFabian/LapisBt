package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream
import java.util.UUID

// The fragments are all of fixed size of 256 bytes
internal sealed interface BluetoothPacket {
	val packetId: UUID // We may use an Int here later if we want to save some space
	val payload: ByteArray

	data class FirstFragment(
		override val packetId: UUID,
		val type: Byte,
		val length: Int,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as FirstFragment

			if (type != other.type) return false
			if (length != other.length) return false
			if (packetId != other.packetId) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = type.toInt()
			result = 31 * result + length
			result = 31 * result + packetId.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}

	data class Fragment(
		override val packetId: UUID,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as Fragment

			if (packetId != other.packetId) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = packetId.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}
}

internal data class CompleteBluetoothPacket(
	val packetId: UUID,
	val type: Type,
	val payloadStream: InputStream,
) {
	enum class Type(val byteValue: Byte) {
		Request(0x01),
		Response(0x02),
		ErrorResponse(0x03),
		Cancellation(0x04);

		companion object {
			private val map = entries.associateBy(Type::byteValue)

			fun fromByte(byte: Byte): Type = map[byte] ?: throw IllegalArgumentException("Unknown type byte: $byte")
		}
	}
}
