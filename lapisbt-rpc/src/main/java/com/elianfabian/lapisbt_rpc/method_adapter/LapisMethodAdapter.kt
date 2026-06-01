package com.elianfabian.lapisbt_rpc.method_adapter

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import java.lang.reflect.Method
import kotlin.reflect.KClass

internal interface LapisMethodAdapter {

	fun getOutputType(method: Method): KClass<*>

	fun shouldIntercept(method: Method): Boolean

	fun functionCall(
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
		onGenerateRequestId: (requestId: Int) -> Unit,
	): Any

	suspend fun onReceiveRequest(request: LapisRequest, server: LapisServerService)

	fun onCancel(requestId: Int)

	/**
	 * Can be called more than once
	 */
	fun onResult(requestId: Int, result: Any?)

	fun onEnd(requestId: Int)

	fun onErrorMessage(requestId: Int, throwable: Throwable)

	fun onDeviceDisconnected(deviceAddress: BluetoothDevice.Address)

	fun dispose()
}
