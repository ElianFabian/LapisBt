package com.elianfabian.lapisbt_rpc.model

internal sealed interface LapisRpcPacket {
	val requestId: Int

	data class Request(
		override val requestId: Int,
		val serviceName: String,
		val methodName: String,
		val rawArguments: Map<String, ByteArray>,
		val rawMetadata: ByteArray,
	) : LapisRpcPacket {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Request) return false
			if (requestId != other.requestId) return false
			if (serviceName != other.serviceName) return false
			if (methodName != other.methodName) return false
			if (rawArguments != other.rawArguments) return false
			if (!rawMetadata.contentEquals(other.rawMetadata)) return false
			return true
		}

		override fun hashCode(): Int {
			var result = requestId.hashCode()
			result = 31 * result + serviceName.hashCode()
			result = 31 * result + methodName.hashCode()
			result = 31 * result + rawArguments.hashCode()
			result = 31 * result + rawMetadata.contentHashCode()
			return result
		}
	}

	data class Response(
		override val requestId: Int,
		val rawData: ByteArray,
	) : LapisRpcPacket {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Response) return false
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

	data class ErrorResponse(
		override val requestId: Int,
		val message: String,
	) : LapisRpcPacket

	data class Cancellation(override val requestId: Int) : LapisRpcPacket

	data class Completion(override val requestId: Int) : LapisRpcPacket

	sealed interface FlowParameter : LapisRpcPacket {
		val flowId: Int
		val parameterName: String

		data class Collection(
			override val requestId: Int,
			override val flowId: Int,
			override val parameterName: String,
		) : FlowParameter

		data class Emission(
			override val requestId: Int,
			override val flowId: Int,
			override val parameterName: String,
			val rawData: ByteArray,
		) : FlowParameter {
			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is Emission) return false
				if (requestId != other.requestId) return false
				if (flowId != other.flowId) return false
				if (parameterName != other.parameterName) return false
				if (!rawData.contentEquals(other.rawData)) return false
				return true
			}

			override fun hashCode(): Int {
				var result = requestId.hashCode()
				result = 31 * result + flowId.hashCode()
				result = 31 * result + parameterName.hashCode()
				result = 31 * result + rawData.contentHashCode()
				return result
			}
		}

		data class Cancellation(
			override val requestId: Int,
			override val flowId: Int,
			override val parameterName: String,
		) : FlowParameter

		data class Completion(
			override val requestId: Int,
			override val flowId: Int,
			override val parameterName: String,
		) : FlowParameter

		data class Error(
			override val requestId: Int,
			override val flowId: Int,
			override val parameterName: String,
			val message: String,
		) : FlowParameter
	}
}
