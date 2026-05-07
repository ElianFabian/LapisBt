package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.LapisMethodExecutionEnd
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object MethodExecutionEndSerializer : LapisSerializer<LapisMethodExecutionEnd> {

	override fun serialize(stream: OutputStream, data: LapisMethodExecutionEnd) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
	}

	override fun deserialize(stream: InputStream): LapisMethodExecutionEnd {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

		return LapisMethodExecutionEnd(
			requestId = requestId,
		)
	}
}
