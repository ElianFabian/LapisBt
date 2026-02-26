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

		dataStream.writeLong(data.uuid.mostSignificantBits)
		dataStream.writeLong(data.uuid.leastSignificantBits)
		//dataStream.writeUTF(data.apiName)
		//dataStream.writeUTF(data.methodName)
		dataStream.writeInt(data.result.size)
		dataStream.write(data.result)
	}

	override fun deserialize(stream: InputStream): LapisResponse {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val uuid = UUID(mostSigBits, leastSigBits)
		val apiName = dataStream.readUTF()
		val methodName = dataStream.readUTF()
		val resultSize = dataStream.readInt()
		val result = ByteArray(resultSize)
		dataStream.readFully(result)

		return LapisResponse(
			uuid = uuid,
			//apiName = apiName,
			//methodName = methodName,
			result = result,
		)
	}
}
