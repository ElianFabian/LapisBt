package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.RawLapisResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object ResponseSerializer : LapisSerializer<RawLapisResponse> {

	override fun serialize(stream: OutputStream, data: RawLapisResponse) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeInt(data.rawData.size)
		dataStream.write(data.rawData)
	}

	override fun deserialize(stream: InputStream): RawLapisResponse {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

		val resultSize = dataStream.readInt()
		val result = ByteArray(resultSize)
		dataStream.readFully(result)

		return RawLapisResponse(
			requestId = requestId,
			rawData = result,
		)
	}
}
