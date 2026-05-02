package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

// The fragments are all of fixed size of 256 bytes
internal sealed interface BluetoothPacket {

	val packetId: UUID // We may use an Int here later if we want to save some space

	data class FirstFragment(
		override val packetId: UUID,
		val type: Byte,
		val length: Int,
		val compressed: Boolean,
		val originalPayloadSize: Int,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as FirstFragment

			if (packetId != other.packetId) return false
			if (type != other.type) return false
			if (length != other.length) return false
			if (compressed != other.compressed) return false
			if (originalPayloadSize != other.originalPayloadSize) return false

			return true
		}

		override fun hashCode(): Int {
			var result = type.hashCode()
			result = 31 * result + length
			result = 31 * result + compressed.hashCode()
			result = 31 * result + originalPayloadSize
			result = 31 * result + packetId.hashCode()
			return result
		}
	}

	data class Fragment(
		override val packetId: UUID,
		// For the index is a relevant data for the logic, but we might change it to get rid of it and save some space
		val index: Int,
		val payload: ByteArray,
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
			var result = index
			result = 31 * result + packetId.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}
}
