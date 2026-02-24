package com.elianfabian.lapisbt_rpc.annotation

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisParam(
	val name: String,
)
