package com.elianfabian.lapisbt_rpc.model

import java.util.UUID
import kotlin.collections.iterator

internal data class LapisRequest(
	val uuid: UUID,
	val apiName: String,
	val methodName: String,
	val arguments: Map<String, ByteArray>,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as LapisRequest

		if (uuid != other.uuid) return false
		if (apiName != other.apiName) return false
		if (methodName != other.methodName) return false
		if (arguments.size != other.arguments.size) return false
		for ((key, value) in arguments) {
			val otherValue = other.arguments[key] ?: return false
			if (!value.contentEquals(otherValue)) return false
		}

		return true
	}

	override fun hashCode(): Int {
		var result1 = uuid.hashCode()
		result1 = 31 * result1 + apiName.hashCode()
		result1 = 31 * result1 + methodName.hashCode()
		for ((key, value) in arguments) {
			result1 = 31 * result1 + key.hashCode()
			result1 = 31 * result1 + value.contentHashCode()
		}
		return result1
	}
}
