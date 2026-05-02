package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

internal class LapisBtRpcImpl(
	private val lapisBt: LapisBt,
	private val serializationStrategy: LapisSerializationStrategy,
	private val interceptor: LapisInterceptor,
	private val metadataProvider: LapisMetadataProvider<Any?>,
	private val createPacketProcessor: (deviceAddress: String) -> LapisPacketProcessor,
) : LapisBtRpc {

	private val _bluetoothClientApisByAddress = ConcurrentHashMap<String, ConcurrentHashMap<KClass<*>, Any>>()
	private val _bluetoothServerApiByAddress = ConcurrentHashMap<String, ConcurrentHashMap<KClass<*>, Any>>()
	private val _bluetoothDeviceRpcByAddress = ConcurrentHashMap<String, BluetoothDeviceRpc>()

	@Volatile
	private var _isDisposed = false


	override fun <T : Any> getOrCreateBluetoothClientApi(deviceAddress: String, apiInterface: KClass<T>): T {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		if (!apiInterface.java.isInterface) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be an interface")
		}
		if (apiInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be annotated with @LapisRpc")
		}

		val clientApisByClass = _bluetoothClientApisByAddress[deviceAddress]
		if (clientApisByClass != null) {
			val apiClient = clientApisByClass[apiInterface]
			if (apiClient != null) {
				@Suppress("UNCHECKED_CAST")
				return apiClient as T
			}
		}

		val bluetoothDeviceRpc = _bluetoothDeviceRpcByAddress.getOrPut(deviceAddress) {
			BluetoothDeviceRpc(
				deviceAddress = deviceAddress,
				lapisBt = lapisBt,
				lapisRpc = this@LapisBtRpcImpl,
				serializationStrategy = serializationStrategy,
				interceptor = interceptor,
				metadataProvider = metadataProvider,
				packetProcessor = createPacketProcessor(deviceAddress),
			)
		}

		@Suppress("UNCHECKED_CAST")
		val newClientApi = Proxy.newProxyInstance(
			apiInterface.java.classLoader,
			arrayOf(apiInterface.java),
			BluetoothApiClientInvocationHandler(
				apiInterface = apiInterface.java,
				bluetoothDeviceRpc = bluetoothDeviceRpc,
			),
		) as T

		val clientApiByClass = _bluetoothClientApisByAddress.getOrPut(deviceAddress) {
			ConcurrentHashMap<KClass<*>, Any>()
		}
		clientApiByClass[apiInterface] = newClientApi

		return newClientApi
	}

	override fun getBluetoothClientApiByName(deviceAddress: String, apiName: String): Any {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		val apiClientsByClass = _bluetoothClientApisByAddress[deviceAddress] ?: throw IllegalStateException("There's no client API registered for address '$deviceAddress'")
		apiClientsByClass.entries.forEach { (apiInterface, clientApi) ->
			val annotation = apiInterface.java.getAnnotation(LapisRpc::class.java) ?: error("API interface ${apiInterface.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			if (annotation.name == apiName) {
				return clientApi
			}
		}

		throw IllegalStateException("There's no client API registered for address '$deviceAddress' and API name '$apiName'")
	}

	override fun <T : Any> unregisterBluetoothClientApi(deviceAddress: String, apiInterface: KClass<T>) {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		val clientApis = _bluetoothClientApisByAddress[deviceAddress] ?: throw IllegalStateException("There's no client API registered for address '$deviceAddress'")
		if (clientApis.remove(apiInterface) == null) {
			throw IllegalStateException("There's no client API registered for address '$deviceAddress' and interface $apiInterface")
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> unregisterBluetoothApiClientsByAddress(deviceAddress: String) {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		if (_bluetoothClientApisByAddress.remove(deviceAddress) == null) {
			throw IllegalStateException("There's no client APIs registered for address '$deviceAddress'")
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> registerBluetoothServerApi(deviceAddress: String, server: T, apiInterface: KClass<T>) {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		if (!apiInterface.java.isInterface) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be an interface")
		}
		if (apiInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("API interface ${apiInterface.qualifiedName} must be annotated with @${LapisRpc::class.simpleName}")
		}

		val serverApiByClass = _bluetoothServerApiByAddress.getOrPut(deviceAddress) {
			ConcurrentHashMap<KClass<*>, Any>()
		}
		val existingServerApi = serverApiByClass[apiInterface]
		if (existingServerApi != null) {
			throw IllegalStateException("Server $existingServerApi for address $deviceAddress and interface $apiInterface already registered")
		}

		serverApiByClass[apiInterface] = server

		if (server is LapisBtRpc.Registered) {
			server.onLapisServiceRegistered(deviceAddress)
		}

		_bluetoothDeviceRpcByAddress.getOrPut(deviceAddress) {
			BluetoothDeviceRpc(
				deviceAddress = deviceAddress,
				lapisBt = lapisBt,
				lapisRpc = this@LapisBtRpcImpl,
				serializationStrategy = serializationStrategy,
				interceptor = interceptor,
				metadataProvider = metadataProvider,
				packetProcessor = createPacketProcessor(deviceAddress),
			)
		}
	}

	override fun getBluetoothServerApiByName(deviceAddress: String, apiName: String): Any {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		val serverApis = _bluetoothServerApiByAddress[deviceAddress] ?: throw IllegalStateException("There's no server API registered for address '$deviceAddress'")

		val serverApi = serverApis.entries.firstOrNull { (serverClass, _) ->
			val annotation = serverClass.java.getAnnotation(LapisRpc::class.java) ?: error("API interface ${serverClass.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			annotation.name == apiName
		}?.value ?: throw IllegalStateException("There's no server API registered for address '$deviceAddress' and API name '$apiName'")

		return serverApi
	}

	override fun <T : Any> unregisterBluetoothServerApi(deviceAddress: String, apiInterface: KClass<T>) {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		val serverApis = _bluetoothServerApiByAddress[deviceAddress] ?: throw IllegalStateException("There's no server API registered for address '$deviceAddress'")
		val server = serverApis.remove(apiInterface) ?: throw IllegalStateException("There's no server API registered for address '$deviceAddress' and interface $apiInterface")

		if (server is LapisBtRpc.Registered) {
			server.onLapisServiceUnregistered(deviceAddress)
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> unregisterBluetoothServerApisByAddress(deviceAddress: String) {
		checkIsNotDisposed()
		requireValidAddress(deviceAddress)

		val removedApisMap = _bluetoothServerApiByAddress.remove(deviceAddress)
			?: throw IllegalStateException("There's no server APIs registered for address '$deviceAddress'")

		removedApisMap.values.forEach { server ->
			if (server is LapisBtRpc.Registered) {
				server.onLapisServiceUnregistered(deviceAddress)
			}
		}

		tryCleanupResources(deviceAddress)
	}

	override fun dispose() {
		if (_isDisposed) {
			return
		}

		_isDisposed = true

		_bluetoothDeviceRpcByAddress.forEach { (_, bridge) ->
			bridge.dispose()
		}
		_bluetoothDeviceRpcByAddress.clear()

		_bluetoothClientApisByAddress.clear()

		_bluetoothServerApiByAddress.forEach { (deviceAddress, serversByClass) ->
			serversByClass.forEach { (_, server) ->
				if (server is LapisBtRpc.Registered) {
					server.onLapisServiceUnregistered(deviceAddress)
				}
			}
		}
		_bluetoothServerApiByAddress.clear()
	}


	private fun requireValidAddress(deviceAddress: String) {
		require(LapisBt.checkBluetoothAddress(deviceAddress)) {
			"The device address '$deviceAddress' is invalid"
		}
	}

	private fun tryCleanupResources(deviceAddress: String) {
		val hasClients = _bluetoothClientApisByAddress[deviceAddress]?.isNotEmpty() == true
		val hasServers = _bluetoothServerApiByAddress[deviceAddress]?.isNotEmpty() == true

		if (!hasClients && !hasServers) {
			val bridge = _bluetoothDeviceRpcByAddress.remove(deviceAddress)

			bridge?.dispose()

			_bluetoothClientApisByAddress.remove(deviceAddress)
			_bluetoothServerApiByAddress.remove(deviceAddress)
		}
	}

	private fun checkIsNotDisposed() {
		check(!_isDisposed) {
			"Can't call any method in ${LapisBtRpc::class.qualifiedName} since it's already disposed"
		}
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
	override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any {
		return bluetoothDeviceRpc.functionCall(
			proxy = proxy,
			apiInterface = apiInterface,
			method = method,
			args = args,
		)
	}
}
