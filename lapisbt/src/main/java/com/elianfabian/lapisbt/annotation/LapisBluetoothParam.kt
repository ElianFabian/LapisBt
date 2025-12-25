package com.elianfabian.lapisbt.annotation

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisBluetoothParam(
	val name: String,
)
