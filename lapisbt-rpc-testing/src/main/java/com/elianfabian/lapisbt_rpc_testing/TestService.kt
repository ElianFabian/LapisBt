package com.elianfabian.lapisbt_rpc_testing

import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import kotlinx.coroutines.flow.Flow

@LapisRpc("TestService")
interface TestService {

	@LapisMethod("greet")
	suspend fun greet(
		@LapisParam("name")
		name: String,
	): String

	@LapisMethod("add")
	suspend fun add(
		@LapisParam("a")
		a: Int,
		@LapisParam("b")
		b: Int,
	): Int

	@LapisMethod("counter")
	fun counter(): Flow<Int>

	@LapisMethod("processFlow")
	suspend fun processFlow(
		@LapisParam("data")
		data: Flow<Int>,
	): Int

	@LapisMethod("getRequestInfo")
	suspend fun getRequestInfo(): String
}

@LapisRpc("SecondaryService")
interface SecondaryService {

	@LapisMethod("ping")
	suspend fun ping(): String
}
