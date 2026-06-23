package com.elianfabian.lapisbt_rpc.model

import java.io.InputStream

/**
 * A fully reassembled Bluetooth packet ready for processing.
 *
 * This class represents the logical packet after fragmentation and reassembly
 * have been handled by the [com.elianfabian.lapisbt_rpc.LapisPacketProcessor].
 *
 * @property packetId A unique identifier for the packet.
 * @property type The logical type of the packet (e.g., Request, Response).
 * @property payloadStream A stream containing the packet's payload data.
 */
public data class CompleteBluetoothPacket(
	val packetId: Int,
	val type: Type,
	val payloadStream: InputStream,
) {
	/**
	 * Defines the possible types of RPC packets.
	 */
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

			/**
			 * Resolves a [Type] from its raw [Byte] value.
			 */
			public fun fromByte(byte: Byte): Type = map[byte] ?: throw IllegalArgumentException("Unknown type byte: $byte")
		}
	}
}
