package com.elianfabian.lapisbt_rpc.model

import java.util.UUID

public data class LapisResponse(
	val requestId: UUID,
	val data: Any?,
)
