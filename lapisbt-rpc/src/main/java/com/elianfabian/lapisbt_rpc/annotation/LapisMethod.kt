package com.elianfabian.lapisbt_rpc.annotation

/**
 * Marks a function within a [LapisRpc] interface as an RPC method.
 *
 * Support methods:
 * - `suspend` functions for one-shot requests/actions.
 * - Functions returning `Flow<T>` for data streaming.
 *
 * @property name The name of the method used to invoke it remotely.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisMethod(
	val name: String,
)
