package com.elianfabian.lapisbt.model

import java.io.InputStream
import java.util.UUID

// The fragments are all of fixed size of 256 bytes
internal sealed interface BluetoothPacket {
	val id: Int
	val index: Int
	val payload: ByteArray

	data class FirstFragment(
		override val id: Int,
		override val index: Int,
		val type: UUID,
		val length: Int,
		override val payload: ByteArray,
	) : BluetoothPacket {

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as FirstFragment

			if (id != other.id) return false
			if (index != other.index) return false
			if (length != other.length) return false
			if (type != other.type) return false
			if (!payload.contentEquals(other.payload)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = id
			result = 31 * result + index
			result = 31 * result + length
			result = 31 * result + type.hashCode()
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}

	data class Fragment(
		override val id: Int,
		override val index: Int,
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
			var result = id
			result = 31 * result + index
			result = 31 * result + payload.contentHashCode()
			return result
		}
	}
}

internal data class CompleteBluetoothPacket(
	val id: Int,
	val type: UUID,
	val payloadStream: InputStream,
)
