package com.elianfabian.lapisbt.serialized_type

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object UuidSerializer : LapisDataSerializer<UUID> {

	override val type = UUID::class

	override fun serialize(stream: OutputStream, data: UUID) {
		val dataStream = DataOutputStream(stream)
		dataStream.writeLong(data.mostSignificantBits)
		dataStream.writeLong(data.leastSignificantBits)
	}

	override fun deserialize(stream: InputStream): UUID {
		val dataStream = DataInputStream(stream)
		val mostSignificantBits = dataStream.readLong()
		val leastSignificantBits = dataStream.readLong()
		return UUID(mostSignificantBits, leastSignificantBits)
	}
}
