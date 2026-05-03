package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class RawLapisRequest(
	val requestId: UUID,
	val serviceName: String,
	val methodName: String,
	val rawArguments: Map<String, ByteArray>,
	val rawMetadata: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as RawLapisRequest

		if (requestId != other.requestId) return false
		if (serviceName != other.serviceName) return false
		if (methodName != other.methodName) return false
		if (rawArguments.size != other.rawArguments.size) return false

		for ((key, value) in rawArguments) {
			val otherValue = other.rawArguments[key] ?: return false
			if (!value.contentEquals(otherValue)) return false
		}

		return rawMetadata.contentEquals(other.rawMetadata)
	}

	override fun hashCode(): Int {
		var result = requestId.hashCode()
		result = 31 * result + serviceName.hashCode()
		result = 31 * result + methodName.hashCode()
		result = 31 * result + rawArguments.entries.sumOf { (key, value) -> key.hashCode() + value.contentHashCode() }
		result = 31 * result + rawMetadata.contentHashCode()
		return result
	}
}
