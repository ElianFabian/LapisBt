package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

internal data class LapisErrorResponse(
	val requestId: UUID,
	val message: String,
)
