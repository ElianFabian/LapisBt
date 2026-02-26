package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.reflect.KClass

internal class LapisBtRpcImpl(
	private val lapisBt: LapisBt,
) : LapisBtRpc {

	private val _bluetoothClientApisByAddress = mutableMapOf<String, Map<KClass<*>, Any>>()
	private val _bluetoothServerApiByInterface = mutableMapOf<KClass<*>, Any>()


	@Suppress("UNCHECKED_CAST")
	override fun <T : Any> getOrCreateBluetoothApiClient(deviceAddress: String, apiInterface: KClass<T>): T {
		if (!apiInterface.java.isInterface) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be an interface")
		}
		if (apiInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be annotated with @LapisRpc")
		}

		val apiClientsByClass = _bluetoothClientApisByAddress[deviceAddress]
		if (apiClientsByClass != null) {
			val apiClient = apiClientsByClass[apiInterface]
			if (apiClient != null) {
				return apiClient as T
			}
		}

		val newApiClient = Proxy.newProxyInstance(
			apiInterface.java.classLoader,
			arrayOf(apiInterface.java),
			BluetoothApiClientInvocationHandler(
				apiInterface = apiInterface.java,
				bluetoothDeviceRpc = BluetoothDeviceRpc(
					deviceAddress = deviceAddress,
					lapisBt = lapisBt,
					lapisRpc = this,
				)
			),
		) as T

		val updatedApiClientsByClass = (apiClientsByClass ?: emptyMap()) + (apiInterface to newApiClient)
		_bluetoothClientApisByAddress[deviceAddress] = updatedApiClientsByClass

		return newApiClient
	}

	override fun getBluetoothApiClientByName(deviceAddress: String, apiName: String): Any? {
		val apiClientsByClass = _bluetoothClientApisByAddress[deviceAddress] ?: return null
		return apiClientsByClass.entries.firstOrNull { (apiInterface, _) ->
			val annotation = apiInterface.java.getAnnotation(LapisRpc::class.java) ?: error("API interface ${apiInterface.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			annotation.name == apiName
		}
	}

	override fun <T : Any> registerBluetoothApiServer(server: T, apiInterface: KClass<T>) {
		if (!apiInterface.java.isInterface) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be an interface")
		}
		if (apiInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be annotated with @${LapisRpc::class.simpleName}")
		}

		if (_bluetoothServerApiByInterface.containsKey(apiInterface)) {
			throw IllegalArgumentException("Server for interface ${apiInterface.qualifiedName} is already registered")
		}

		_bluetoothServerApiByInterface[apiInterface] = server
	}

	override fun getBluetoothApiServerByName(apiName: String): Any? {
		val entry = _bluetoothServerApiByInterface.entries.firstOrNull { (serverClass, _) ->
			val annotation = serverClass.java.getAnnotation(LapisRpc::class.java) ?: error("API interface ${serverClass.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			annotation.name == apiName
		} ?: return null

		@Suppress("UNCHECKED_CAST")
		return entry.value
	}

	override fun unregisterBluetoothApiClient(deviceAddress: String) {
		_bluetoothClientApisByAddress.remove(deviceAddress)
	}

	override fun unregisterBluetoothApiServer(server: Any) {
		val serverClass = server::class
		if (!_bluetoothServerApiByInterface.containsKey(serverClass)) {
			throw IllegalArgumentException("Server for interface ${serverClass.qualifiedName} is not registered")
		}

		_bluetoothServerApiByInterface.remove(serverClass)
	}


	companion object {
		const val BLUETOOTH_PACKET_LENGTH = 256
		//val Tag = LapisBtRpcImpl::class.simpleName.orEmpty()
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
