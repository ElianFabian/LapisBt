package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

public data class LapisRequest(
	val requestId: UUID,
	val serviceName: String,
	val methodName: String,
	val arguments: Map<String, Any?>,
	val metadata: Any?,
)
