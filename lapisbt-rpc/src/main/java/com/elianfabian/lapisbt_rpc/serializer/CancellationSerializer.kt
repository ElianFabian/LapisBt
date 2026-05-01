package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object CancellationSerializer : LapisSerializer<LapisCancellation> {

	override fun serialize(stream: OutputStream, data: LapisCancellation) {
		val dataStream = DataOutputStream(stream)

		dataStream.writeLong(data.requestId.mostSignificantBits)
		dataStream.writeLong(data.requestId.leastSignificantBits)
	}

	override fun deserialize(stream: InputStream): LapisCancellation {
		val dataStream = DataInputStream(stream)

		val mostSigBits = dataStream.readLong()
		val leastSigBits = dataStream.readLong()
		val requestId = UUID(mostSigBits, leastSigBits)

		return LapisCancellation(
			requestId = requestId,
		)
	}
}
