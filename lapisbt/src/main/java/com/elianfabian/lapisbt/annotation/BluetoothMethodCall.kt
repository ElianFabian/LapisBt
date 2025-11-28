package com.elianfabian.lapisbt.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class BluetoothMethodCall(
	val name: String,
)
