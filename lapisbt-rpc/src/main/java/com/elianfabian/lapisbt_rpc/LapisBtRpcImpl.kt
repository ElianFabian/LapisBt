package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
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
	private val createPacketProcessor: (deviceAddress: BluetoothDevice.Address) -> LapisPacketProcessor,
) : LapisBtRpc {

	private val _bluetoothClientServicesByAddress = ConcurrentHashMap<BluetoothDevice.Address, ConcurrentHashMap<KClass<*>, Any>>()
	private val _bluetoothServerServiceByAddress = ConcurrentHashMap<BluetoothDevice.Address, ConcurrentHashMap<KClass<*>, Any>>()
	private val _bluetoothDeviceRpcByAddress = ConcurrentHashMap<BluetoothDevice.Address, BluetoothDeviceRpc>()

	@Volatile
	private var _isDisposed = false


	override fun <T : Any> getOrCreateBluetoothClientService(deviceAddress: BluetoothDevice.Address, serviceInterface: KClass<T>): T {
		checkIsNotDisposed()

		if (!serviceInterface.java.isInterface) {
			throw IllegalArgumentException("Service interface ${serviceInterface.qualifiedName} must be an interface")
		}
		if (serviceInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("Service interface ${serviceInterface.qualifiedName} must be annotated with @LapisRpc")
		}

		val clientApisByClass = _bluetoothClientServicesByAddress[deviceAddress]
		if (clientApisByClass != null) {
			val clientService = clientApisByClass[serviceInterface]
			if (clientService != null) {
				@Suppress("UNCHECKED_CAST")
				return clientService as T
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
		val newClientService = Proxy.newProxyInstance(
			serviceInterface.java.classLoader,
			arrayOf(serviceInterface.java),
			BluetoothApiClientInvocationHandler(
				serviceInterface = serviceInterface.java,
				bluetoothDeviceRpc = bluetoothDeviceRpc,
			),
		) as T

		val clientServiceByClass = _bluetoothClientServicesByAddress.getOrPut(deviceAddress) {
			ConcurrentHashMap<KClass<*>, Any>()
		}
		clientServiceByClass[serviceInterface] = newClientService

		return newClientService
	}

	override fun getBluetoothClientServiceByName(deviceAddress: BluetoothDevice.Address, serviceName: String): Any {
		checkIsNotDisposed()

		val clientServicesByClass = _bluetoothClientServicesByAddress[deviceAddress] ?: throw IllegalStateException("There's no client service registered for address '$deviceAddress'")
		clientServicesByClass.entries.forEach { (serviceInterface, clientService) ->
			val annotation = serviceInterface.java.getAnnotation(LapisRpc::class.java) ?: error("Service interface ${serviceInterface.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			if (annotation.name == serviceName) {
				return clientService
			}
		}

		throw IllegalStateException("There's no client service registered for address '$deviceAddress' and service name '$serviceName'")
	}

	override fun <T : Any> unregisterBluetoothClientService(deviceAddress: BluetoothDevice.Address, serviceInterface: KClass<T>) {
		checkIsNotDisposed()

		val clientServices = _bluetoothClientServicesByAddress[deviceAddress] ?: throw IllegalStateException("There's no client service registered for address '$deviceAddress'")
		if (clientServices.remove(serviceInterface) == null) {
			throw IllegalStateException("There's no client service registered for address '$deviceAddress' and interface $serviceInterface")
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> unregisterBluetoothServiceClientsByAddress(deviceAddress: BluetoothDevice.Address) {
		checkIsNotDisposed()

		if (_bluetoothClientServicesByAddress.remove(deviceAddress) == null) {
			throw IllegalStateException("There's no client services registered for address '$deviceAddress'")
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> registerBluetoothServerService(deviceAddress: BluetoothDevice.Address, server: T, serviceInterface: KClass<T>) {
		checkIsNotDisposed()

		if (!serviceInterface.java.isInterface) {
			throw IllegalArgumentException("Service interface ${serviceInterface.qualifiedName} must be an interface")
		}
		if (serviceInterface.java.getAnnotation(LapisRpc::class.java) == null) {
			throw IllegalArgumentException("Service interface ${serviceInterface.qualifiedName} must be annotated with @${LapisRpc::class.simpleName}")
		}

		val serverApiByClass = _bluetoothServerServiceByAddress.getOrPut(deviceAddress) {
			ConcurrentHashMap<KClass<*>, Any>()
		}
		val existingServerApi = serverApiByClass[serviceInterface]
		if (existingServerApi != null) {
			throw IllegalStateException("Server $existingServerApi for address $deviceAddress and interface $serviceInterface already registered")
		}

		serverApiByClass[serviceInterface] = server

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

	override fun getBluetoothServerServiceByName(deviceAddress: BluetoothDevice.Address, serviceName: String): Any {
		checkIsNotDisposed()

		val serverServices = _bluetoothServerServiceByAddress[deviceAddress] ?: throw IllegalStateException("There's no server service registered for address '$deviceAddress'")

		val serverService = serverServices.entries.firstOrNull { (serverClass, _) ->
			val annotation = serverClass.java.getAnnotation(LapisRpc::class.java) ?: error("Service interface ${serverClass.qualifiedName} is missing @${LapisRpc::class.simpleName} annotation")
			annotation.name == serviceName
		}?.value ?: throw IllegalStateException("There's no server service registered for address '$deviceAddress' and service name '$serviceName'")

		return serverService
	}

	override fun <T : Any> unregisterBluetoothServerService(deviceAddress: BluetoothDevice.Address, serviceInterface: KClass<T>) {
		checkIsNotDisposed()

		val serverServices = _bluetoothServerServiceByAddress[deviceAddress] ?: throw IllegalStateException("There's no server service registered for address '$deviceAddress'")
		val server = serverServices.remove(serviceInterface) ?: throw IllegalStateException("There's no server service registered for address '$deviceAddress' and interface $serviceInterface")

		if (server is LapisBtRpc.Registered) {
			server.onLapisServiceUnregistered(deviceAddress)
		}

		tryCleanupResources(deviceAddress)
	}

	override fun <T : Any> unregisterBluetoothServerServicesByAddress(deviceAddress: BluetoothDevice.Address) {
		checkIsNotDisposed()

		val removedServicesMap = _bluetoothServerServiceByAddress.remove(deviceAddress)
			?: throw IllegalStateException("There's no server services registered for address '$deviceAddress'")

		removedServicesMap.values.forEach { server ->
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

		_bluetoothClientServicesByAddress.clear()

		_bluetoothServerServiceByAddress.forEach { (deviceAddress, serversByClass) ->
			serversByClass.forEach { (_, server) ->
				if (server is LapisBtRpc.Registered) {
					server.onLapisServiceUnregistered(deviceAddress)
				}
			}
		}
		_bluetoothServerServiceByAddress.clear()
	}


	private fun tryCleanupResources(deviceAddress: BluetoothDevice.Address) {
		val hasClients = _bluetoothClientServicesByAddress[deviceAddress]?.isNotEmpty() == true
		val hasServers = _bluetoothServerServiceByAddress[deviceAddress]?.isNotEmpty() == true

		if (!hasClients && !hasServers) {
			val bridge = _bluetoothDeviceRpcByAddress.remove(deviceAddress)

			bridge?.dispose()

			_bluetoothClientServicesByAddress.remove(deviceAddress)
			_bluetoothServerServiceByAddress.remove(deviceAddress)
		}
	}

	private fun checkIsNotDisposed() {
		check(!_isDisposed) {
			"Can't call any method in ${LapisBtRpc::class.qualifiedName} since it's already disposed"
		}
	}
}


private class BluetoothApiClientInvocationHandler(
	private val bluetoothDeviceRpc: BluetoothDeviceRpc,
	private val serviceInterface: Class<*>,
) : InvocationHandler {

	@Suppress("UNCHECKED_CAST")
	override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any {
		return bluetoothDeviceRpc.functionCall(
			proxy = proxy,
			serviceInterface = serviceInterface,
			method = method,
			args = args,
		)
	}
}
