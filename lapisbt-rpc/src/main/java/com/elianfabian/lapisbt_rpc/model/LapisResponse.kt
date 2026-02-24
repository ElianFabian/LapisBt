package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class LapisResponse(
	val uuid: UUID,
	val apiName: String,
	val methodName: String,
	val result: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as LapisResponse

		if (uuid != other.uuid) return false
		if (apiName != other.apiName) return false
		if (methodName != other.methodName) return false
		if (!result.contentEquals(other.result)) return false

		return true
	}

	override fun hashCode(): Int {
		var result1 = uuid.hashCode()
		result1 = 31 * result1 + apiName.hashCode()
		result1 = 31 * result1 + methodName.hashCode()
		result1 = 31 * result1 + result.contentHashCode()
		return result1
	}
}
