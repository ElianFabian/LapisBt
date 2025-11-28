package com.elianfabian.lapisbt.annotation

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BluetoothParam(
	val name: String,
)
