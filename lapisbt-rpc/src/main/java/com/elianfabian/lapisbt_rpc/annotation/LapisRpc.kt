package com.elianfabian.lapisbt_rpc.annotation

/**
 * Marks an interface as a Lapis RPC service.
 *
 * @property name The name of the RPC service used for identification during communication.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisRpc(
	val name: String,
)
