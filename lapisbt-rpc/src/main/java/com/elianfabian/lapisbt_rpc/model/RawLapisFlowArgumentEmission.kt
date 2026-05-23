package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class RawLapisFlowArgumentEmission(
	val flowId: UUID,
	val parameterName: String,
	val requestId: UUID,
	val rawValue: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as RawLapisFlowArgumentEmission

		if (flowId != other.flowId) return false
		if (parameterName != other.parameterName) return false
		if (requestId != other.requestId) return false
		if (!rawValue.contentEquals(other.rawValue)) return false

		return true
	}

	override fun hashCode(): Int {
		var result = flowId.hashCode()
		result = 31 * result + parameterName.hashCode()
		result = 31 * result + requestId.hashCode()
		result = 31 * result + rawValue.contentHashCode()
		return result
	}
}
