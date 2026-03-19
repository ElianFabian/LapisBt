package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.LapisSerializer
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object ErrorResponseSerializer : LapisSerializer<LapisErrorResponse> {

	override fun serialize(stream: OutputStream, data: LapisErrorResponse) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeUTF(data.message)
	}

	override fun deserialize(stream: InputStream): LapisErrorResponse {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

		val message = dataStream.readUTF()

		return LapisErrorResponse(
			requestId = requestId,
			message = message,
		)
	}
}
