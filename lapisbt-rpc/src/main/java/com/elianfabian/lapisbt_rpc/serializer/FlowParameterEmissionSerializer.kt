package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.RawLapisFlowArgumentEmission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object FlowParameterEmissionSerializer : LapisSerializer<RawLapisFlowArgumentEmission> {

	override fun serialize(stream: OutputStream, data: RawLapisFlowArgumentEmission) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data.flowId.mostSignificantBits)
		dataStream.writeLong(data.flowId.leastSignificantBits)
		dataStream.writeUTF(data.parameterName)
		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeInt(data.rawValue.size)
		dataStream.write(data.rawValue)
	}

	override fun deserialize(stream: InputStream): RawLapisFlowArgumentEmission {
		val dataStream = DataInputStream(stream)
		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val id = java.util.UUID(mostSigBits, leastSigBits)

		val parameterName = dataStream.readUTF()

		val requestMostSigBits = dataStream.readLong()
		val requestLeastSigBits = dataStream.readLong()
		val requestId = java.util.UUID(requestMostSigBits, requestLeastSigBits)

		val rawValueSize = dataStream.readInt()
		val rawValue = ByteArray(rawValueSize)
		dataStream.readFully(rawValue)

		return RawLapisFlowArgumentEmission(
			flowId = id,
			parameterName = parameterName,
			requestId = requestId,
			rawValue = rawValue,
		)
	}
}
