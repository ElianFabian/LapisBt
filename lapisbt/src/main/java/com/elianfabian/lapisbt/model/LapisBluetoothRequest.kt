package com.elianfabian.lapisbt.model

import java.util.UUID

internal data class LapisBluetoothRequest(
	val uuid: UUID,
	val apiName: String,
	val methodName: String,
	val arguments: Map<String, Any?>,
)
