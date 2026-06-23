package com.elianfabian.lapisbt_rpc.model

/**
 * Represents a high-level RPC response.
 *
 * This object contains the result of an RPC method invocation.
 *
 * @property requestId The ID of the request this response corresponds to.
 * @property data The deserialized return value of the method.
 */
public data class LapisResponse(
	val requestId: Int,
	val data: Any?,
)
