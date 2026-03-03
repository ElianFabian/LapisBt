package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.LapisSerializer
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object ResponseSerializer : LapisSerializer<LapisResponse> {

	override fun serialize(stream: OutputStream, data: LapisResponse) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeInt(data.data.size)
		dataStream.write(data.data)
	}

	override fun deserialize(stream: InputStream): LapisResponse {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

		val resultSize = dataStream.readInt()
		val result = ByteArray(resultSize)
		dataStream.readFully(result)

		return LapisResponse(
			requestId = requestId,
			data = result,
		)
	}
}
