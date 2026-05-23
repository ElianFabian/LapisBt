package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.LapisFlowParameterError
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object FlowParameterErrorSerializer : LapisSerializer<LapisFlowParameterError> {

	override fun serialize(stream: OutputStream, data: LapisFlowParameterError) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data.flowId.mostSignificantBits)
		dataStream.writeLong(data.flowId.leastSignificantBits)
		dataStream.writeUTF(data.parameterName)
		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
		dataStream.writeUTF(data.message)
	}

	override fun deserialize(stream: InputStream): LapisFlowParameterError {
		val dataStream = DataInputStream(stream)
		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val id = UUID(mostSigBits, leastSigBits)

		val parameterName = dataStream.readUTF()

		val requestMostSigBits = dataStream.readLong()
		val requestLeastSigBits = dataStream.readLong()
		val requestId = UUID(requestMostSigBits, requestLeastSigBits)

		val message = dataStream.readUTF()

		return LapisFlowParameterError(
			flowId = id,
			parameterName = parameterName,
			requestId = requestId,
			message = message,
		)
	}
}
