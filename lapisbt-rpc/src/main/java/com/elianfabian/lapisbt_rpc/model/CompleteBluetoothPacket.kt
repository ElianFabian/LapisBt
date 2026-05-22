package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream
import java.util.UUID

public data class CompleteBluetoothPacket(
	val packetId: UUID,
	val type: Type,
	val payloadStream: InputStream,
) {
	public enum class Type(public val byteValue: Byte) {
		Request(1),
		Response(2),
		ErrorResponse(3),
		Cancellation(4),
		Completion(5),
		ArgumentFlowCollection(6),
		ArgumentFlowEmission(7),
		ArgumentFlowCompletion(8),
		ArgumentFlowCancellation(9),
		ArgumentFlowError(10);

		public companion object {
			private val map = entries.associateBy(Type::byteValue)

			public fun fromByte(byte: Byte): Type = map[byte] ?: throw IllegalArgumentException("Unknown type byte: $byte")
		}
	}
}
