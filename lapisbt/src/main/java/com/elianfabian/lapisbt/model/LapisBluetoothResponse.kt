package com.elianfabian.lapisbt.model

internal data class LapisBluetoothResponse(
	val uuid: String,
	val apiName: String,
	val methodName: String,
	val result: Any?,
)
