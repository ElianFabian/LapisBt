package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream
import java.util.UUID

// The fragments are all of fixed size of 256 bytes
internal sealed interface BluetoothPacket {
	val id: UUID // We may use an Int here later if we want to save some space
	val payload: ByteArray

	data class FirstFragment(
		override val id: UUID,
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
			if (id != other.id) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = type.toInt()
			result = 31 * result + length
			result = 31 * result + id.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}

	data class Fragment(
		override val id: UUID,
		// For now, we'll use this index for debugging purposes, but I guess this should not be necessary
		val index: Int,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as Fragment

			if (id != other.id) return false
			if (index != other.index) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = index
			result = 31 * result + id.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}
}

internal data class CompleteBluetoothPacket(
	val id: UUID,
	val type: Byte,
	val payloadStream: InputStream,
) {
	companion object {
		const val TYPE_REQUEST: Byte = 0x01
		const val TYPE_RESPONSE: Byte = 0x02
	}
}
