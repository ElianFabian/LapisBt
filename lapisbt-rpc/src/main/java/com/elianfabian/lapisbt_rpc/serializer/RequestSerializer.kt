package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.LapisSerializer
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object RequestSerializer : LapisSerializer<LapisRequest> {

	override fun serialize(stream: OutputStream, data: LapisRequest) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.uuid.mostSignificantBits)
		dataStream.writeLong(data.uuid.leastSignificantBits)
		dataStream.writeUTF(data.apiName)
		dataStream.writeUTF(data.methodName)
		dataStream.writeInt(data.arguments.size)
		for ((key, value) in data.arguments) {
			dataStream.writeUTF(key)
			dataStream.writeInt(value.size)
			dataStream.write(value)
		}
	}

	override fun deserialize(stream: InputStream): LapisRequest {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val uuid = UUID(mostSigBits, leastSigBits)
		val apiName = dataStream.readUTF()
		val methodName = dataStream.readUTF()
		val argumentsSize = dataStream.readInt()
		val arguments = mutableMapOf<String, ByteArray>()

		repeat(argumentsSize) {
			val key = dataStream.readUTF()
			val valueSize = dataStream.readInt()
			val value = ByteArray(valueSize)
			dataStream.readFully(value)
			arguments[key] = value
		}

		return LapisRequest(
			uuid = uuid,
			apiName = apiName,
			methodName = methodName,
			arguments = arguments,
		)
	}
}
