package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class RawLapisResponse(
	val requestId: UUID,
	val rawData: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as RawLapisResponse

		if (requestId != other.requestId) return false
		if (!rawData.contentEquals(other.rawData)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = requestId.hashCode()
		result = 31 * result + rawData.contentHashCode()
		return result
	}
}
