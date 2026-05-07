package com.elianfabian.lapisbt_rpc.method_adapter

internal interface LapisServerService {
	public fun invokeMethod(): Any?
	public suspend fun invokeSuspendMethod(): Any?
}
