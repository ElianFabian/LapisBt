package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.RawLapisRequest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object RequestSerializer : LapisSerializer<RawLapisRequest> {

	override fun serialize(stream: OutputStream, data: RawLapisRequest) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeUTF(data.apiName)
		dataStream.writeUTF(data.methodName)
		dataStream.writeInt(data.rawArguments.size)

		for ((key, value) in data.rawArguments) {
			dataStream.writeUTF(key)
			dataStream.writeInt(value.size)
			dataStream.write(value)
		}

		dataStream.writeInt(data.rawMetadata.size)
		dataStream.write(data.rawMetadata)
	}

	override fun deserialize(stream: InputStream): RawLapisRequest {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

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

		val metadataSize = dataStream.readInt()
		val metadata = ByteArray(metadataSize)
		dataStream.readFully(metadata)

		return RawLapisRequest(
			requestId = requestId,
			apiName = apiName,
			methodName = methodName,
			rawArguments = arguments,
			rawMetadata = metadata,
		)
	}
}
