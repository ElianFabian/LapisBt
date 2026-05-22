package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class LapisArgumentFlowError(
	val flowId: UUID,
	val parameterName: String,
	val requestId: UUID,
	val message: String,
)
