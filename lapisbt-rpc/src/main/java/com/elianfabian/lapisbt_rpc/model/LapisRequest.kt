package com.elianfabian.lapisbt_rpc.model

public data class LapisRequest(
	val requestId: Int,
	val serviceName: String,
	val methodName: String,
	val arguments: Map<String, Any?>,
	val metadata: Any?,
)
