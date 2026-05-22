package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.LapisArgumentFlowCompletion
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object ArgumentFlowCompletionSerializer : LapisSerializer<LapisArgumentFlowCompletion> {

	override fun serialize(stream: OutputStream, data: LapisArgumentFlowCompletion) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data.flowId.mostSignificantBits)
		dataStream.writeLong(data.flowId.leastSignificantBits)
		dataStream.writeUTF(data.parameterName)
		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
	}

	override fun deserialize(stream: InputStream): LapisArgumentFlowCompletion {
		val dataStream = DataInputStream(stream)
		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val id = UUID(mostSigBits, leastSigBits)

		val parameterName = dataStream.readUTF()

		val requestMostSigBits = dataStream.readLong()
		val requestLeastSigBits = dataStream.readLong()

		val requestId = UUID(requestMostSigBits, requestLeastSigBits)

		return LapisArgumentFlowCompletion(
			flowId = id,
			parameterName = parameterName,
			requestId = requestId,
		)
	}
}
