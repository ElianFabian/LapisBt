package com.elianfabian.lapisbt_rpc.model

internal sealed interface BluetoothPacket {

	val packetId: Int
	val payload: ByteArray

	data class FirstFragment(
		override val packetId: Int,
		val type: Byte,
		val length: Int,
		val compressed: Boolean,
		val encrypted: Boolean,
		val originalPayloadSize: Int,
		val actualPayloadSize: Int,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as FirstFragment

			if (packetId != other.packetId) return false
			if (type != other.type) return false
			if (length != other.length) return false
			if (compressed != other.compressed) return false
			if (encrypted != other.encrypted) return false
			if (originalPayloadSize != other.originalPayloadSize) return false
			if (actualPayloadSize != other.actualPayloadSize) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = packetId
			result = 31 * result + type.hashCode()
			result = 31 * result + length
			result = 31 * result + compressed.hashCode()
			result = 31 * result + encrypted.hashCode()
			result = 31 * result + originalPayloadSize
			result = 31 * result + actualPayloadSize
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}

	data class Fragment(
		override val packetId: Int,
		val index: Int,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as Fragment

			if (packetId != other.packetId) return false
			if (index != other.index) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = packetId
			result = 31 * result + index
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}
}
