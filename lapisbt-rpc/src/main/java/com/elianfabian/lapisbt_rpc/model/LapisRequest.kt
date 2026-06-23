package com.elianfabian.lapisbt_rpc.model

/**
 * Represents a high-level RPC request.
 *
 * This object contains all the information needed to invoke a method on a
 * remote RPC service.
 *
 * @property requestId A unique identifier for the request, used to match responses.
 * @property serviceName The name of the [LapisRpc] service.
 * @property methodName The name of the [LapisMethod] to invoke.
 * @property arguments A map of parameter names to their deserialized values.
 * @property metadata Optional metadata associated with the request.
 */
public data class LapisRequest(
	val requestId: Int,
	val serviceName: String,
	val methodName: String,
	val arguments: Map<String, Any?>,
	val metadata: Any?,
)
