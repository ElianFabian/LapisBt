package com.elianfabian.lapisbt.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisBluetoothMethodCall(
	val name: String,
)
