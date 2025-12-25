package com.elianfabian.lapisbt.model

import java.util.UUID


internal data class BluetoothMethodRequest(
	val id: Int,
	val apiName: String,
	val methodName: String,
	val arguments: Map<String, Any>,
)

internal data class BluetoothMethodResponse(
	val id: Int,
	val apiName: String,
	val methodName: String,
	val type: UUID,
	val result: Any?,
)
