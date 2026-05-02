package com.elianfabian.lapisbt_rpc.annotation

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisRpc(
	val name: String,
)
