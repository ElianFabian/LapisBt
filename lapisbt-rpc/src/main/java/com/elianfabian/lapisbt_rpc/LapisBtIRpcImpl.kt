package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal class LapisBtIRpcImpl(
	private val lapisBt: LapisBt,
) : LapisBtRpc {

	private val _bluetoothClientApiByAddress = mutableMapOf<String, Any>()

	private val _serializationStrategy: SerializationStrategy = DefaultSerializationStrategy

//	private val _serializerRegistry = LapisTypeSerializerRegistry().apply {
//
//	}


	@Suppress("UNCHECKED_CAST")
	override fun <T> getOrCreateBluetoothApiClient(deviceAddress: String, apiInterface: Class<T>): T {
		val apiClient = _bluetoothClientApiByAddress[deviceAddress]
		if (apiClient != null) {
			return apiClient as T
		}

		//_serializationStrategy.serializerForValue(1).serialize()

		val newApiClient = Proxy.newProxyInstance(
			apiInterface.classLoader,
			arrayOf(apiInterface),
			BluetoothApiClientInvocationHandler(
				apiInterface = apiInterface,
				bluetoothDeviceRpc = BluetoothDeviceRpc(
					deviceAddress = deviceAddress,
					lapisBt = lapisBt,
					lapisRpc = this,
					//serializerRegistry = _serializerRegistry,
				)
			),
		) as T

		_bluetoothClientApiByAddress[deviceAddress] = newApiClient as Any

		return newApiClient
	}

	override fun <T : Any> registerBluetoothApiServer(server: T) {
		TODO("Not yet implemented")
	}

	override fun unregisterBluetoothApiClient(deviceAddress: String) {
		TODO("Not yet implemented")
	}

	override fun unregisterBluetoothApiServer(server: Any) {
		TODO("Not yet implemented")
	}


	companion object {
		const val BLUETOOTH_PACKET_LENGTH = 256
		val Tag = LapisBtIRpcImpl::class.simpleName.orEmpty()
	}
}

// Given that we may support multiple APIs in the future, we have to send the request from the InvocationHandler,
// but read the requests in a common place
// so that we can route them to the correct API server implementation
private class BluetoothApiClientInvocationHandler(
	private val bluetoothDeviceRpc: BluetoothDeviceRpc,
	private val apiInterface: Class<*>,
) : InvocationHandler {



	@Suppress("UNCHECKED_CAST")
	override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
		return bluetoothDeviceRpc.functionCall(
			proxy = proxy,
			apiInterface = apiInterface,
			method = method,
			args = args,
		)
	}
}
