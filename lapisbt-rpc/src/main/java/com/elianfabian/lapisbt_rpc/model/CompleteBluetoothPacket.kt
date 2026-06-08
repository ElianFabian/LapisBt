package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream

public data class CompleteBluetoothPacket(
	val packetId: Int,
	val type: Type,
	val payloadStream: InputStream,
) {
	public enum class Type(public val byteValue: Byte) {
		Request(1),
		Response(2),
		ErrorResponse(3),
		Cancellation(4),
		Completion(5),
		FlowParameterCollection(6),
		FlowParameterEmission(7),
		FlowParameterCompletion(8),
		FlowParameterCancellation(9),
		FlowParameterError(10),
		Handshake(11);

		public companion object {
			private val map = entries.associateBy(Type::byteValue)

			public fun fromByte(byte: Byte): Type = map[byte] ?: throw IllegalArgumentException("Unknown type byte: $byte")
		}
	}
}
