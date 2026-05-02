package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream
import java.util.UUID

public data class CompleteBluetoothPacket(
	val packetId: UUID,
	val type: Type,
	val payloadStream: InputStream,
) {
	public enum class Type(public val byteValue: Byte) {
		Request(0x01),
		Response(0x02),
		ErrorResponse(0x03),
		Cancellation(0x04);

		public companion object {
			private val map = entries.associateBy(Type::byteValue)

			public fun fromByte(byte: Byte): Type = map[byte] ?: throw IllegalArgumentException("Unknown type byte: $byte")
		}
	}
}
