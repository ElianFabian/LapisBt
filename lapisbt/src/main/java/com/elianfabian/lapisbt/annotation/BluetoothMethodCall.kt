package com.elianfabian.lapisbt.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class BluetoothMethodCall(
	val name: String,
)
