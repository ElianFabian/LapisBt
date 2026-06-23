package com.elianfabian.lapisbt_rpc.annotation

/**
 * Marks a value parameter within a [LapisMethod] as an RPC parameter.
 *
 * Supports:
 * - Regular types for one-shot values.
 * - `Flow<T>` for data streaming (e.g., input streams).
 *
 * @property name The name of the parameter used for serialization and remote invocation.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisParam(
	val name: String,
)
