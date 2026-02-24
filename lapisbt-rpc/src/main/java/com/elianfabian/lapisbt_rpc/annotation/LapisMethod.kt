package com.elianfabian.lapisbt_rpc.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisMethod(
	val name: String,
)
