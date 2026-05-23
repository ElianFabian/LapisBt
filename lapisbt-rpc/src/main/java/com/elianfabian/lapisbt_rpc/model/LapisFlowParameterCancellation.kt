package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class LapisFlowParameterCancellation(
	val flowId: UUID,
	val parameterName: String,
	val requestId: UUID,
)
