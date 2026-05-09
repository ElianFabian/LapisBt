package com.elianfabian.lapisbt_rpc.method_adapter

import com.elianfabian.lapisbt_rpc.model.LapisRequest
import java.lang.reflect.Method
import java.util.UUID
import kotlin.reflect.KClass

// TODO: I have to rethink interceptors
// TODO: for now this will be an internal API, but maybe this will be public any time soon
internal interface LapisMethodAdapter {

	public fun getOutputType(method: Method): KClass<*>

	public fun shouldIntercept(method: Method): Boolean

	public fun functionCall(
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
		onGenerateRequestId: (requestId: UUID) -> Unit,
	): Any

	public suspend fun onReceiveRequest(request: LapisRequest, server: LapisServerService)

	public fun onCancel(requestId: UUID)

	/**
	 * Can be called more than once
	 */
	public fun onResult(requestId: UUID, result: Any?)

	public fun onEnd(requestId: UUID)

	public fun onErrorMessage(requestId: UUID, throwable: Throwable)

	// TODO: I have to test this
	public fun onDeviceDisconnected(deviceAddress: String)

	public fun onRegister()

	public fun onUnregister()
}
