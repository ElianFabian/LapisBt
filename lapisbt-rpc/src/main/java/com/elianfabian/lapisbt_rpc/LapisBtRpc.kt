package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import kotlin.reflect.KClass

/**
 * A remote procedure call (RPC) layer built on top of [LapisBt].
 *
 * This interface allows for high-level, type-safe communication between devices
 * using standard Kotlin interfaces. It abstracts away the complexities of
 * byte buffers, streams, and manual serialization.
 *
 * ### How it works:
 * 1. **Define** an interface with [@LapisRpc] and [@LapisMethod] annotations.
 * 2. **Server-side:** Register an implementation using [registerBluetoothServerService].
 * 3. **Client-side:** Obtain a proxy implementation using [getOrCreateBluetoothClientService]
 * to call methods remotely.
 */
public interface LapisBtRpc {

	/**
	 * Returns a proxy implementation of [serviceInterface] for the specified [deviceAddress].
	 *
	 * Calling methods on the returned object will serialize the arguments and
	 * transmit them to the remote device for execution.
	 *
	 * @param deviceAddress The MAC address of the remote server.
	 * @param serviceInterface The interface class annotated with [@LapisRpc].
	 * @return A proxy instance of [T].
	 */
	public fun <T : Any> getOrCreateBluetoothClientService(deviceAddress: String, serviceInterface: KClass<T>): T

	public fun getBluetoothClientServiceByName(deviceAddress: String, serviceName: String): Any

	public fun <T : Any> unregisterBluetoothClientService(deviceAddress: String, serviceInterface: KClass<T>)

	public fun <T : Any> unregisterBluetoothServiceClientsByAddress(deviceAddress: String)

	/**
	 * Registers a local implementation of an RPC interface to handle incoming requests.
	 *
	 * Once registered, any calls from the [deviceAddress] targeting this [serviceInterface]
	 * will be routed to the provided [server] instance.
	 *
	 * @param deviceAddress The MAC address of the device allowed to call this server.
	 * @param server The concrete implementation of the RPC logic.
	 * @param serviceInterface The interface class that defines the contract.
	 */
	public fun <T : Any> registerBluetoothServerService(deviceAddress: String, server: T, serviceInterface: KClass<T>)

	public fun getBluetoothServerServiceByName(deviceAddress: String, serviceName: String): Any

	public fun <T : Any> unregisterBluetoothServerService(deviceAddress: String, serviceInterface: KClass<T>)

	public fun <T : Any> unregisterBluetoothServerServicesByAddress(deviceAddress: String)

	/**
	 * Releases all resources held by this instance.
	 *
	 * Once called, this instance is no longer usable.
	 */
	public fun dispose()


	public companion object {

		/**
		 * Creates a new instance of [LapisBtRpc].
		 *
		 * To ensure state consistency, you should maintain a single instance of this interface
		 * throughout your application's lifecycle.
		 */
		public fun newInstance(
			lapisBt: LapisBt,
			serializationStrategy: LapisSerializationStrategy? = null,
			interceptor: LapisInterceptor? = null,
			metadataProvider: LapisMetadataProvider<Any?>? = null,
			createLapisPacketProcessor: ((deviceAddress: String) -> LapisPacketProcessor)? = null,
		): LapisBtRpc {
			return LapisBtRpcImpl(
				lapisBt = lapisBt,
				serializationStrategy = serializationStrategy?.withDefaultFallback() ?: DefaultSerializationStrategy,
				interceptor = interceptor ?: NoOpLapisInterceptor,
				metadataProvider = metadataProvider ?: NoOpLapisMetadataProvider,
				createPacketProcessor = createLapisPacketProcessor ?: { DefaultLapisPacketProcessor() },
			)
		}
	}


	/**
	 * Lifecycle callbacks for monitoring the availability of RPC services.
	 */
	public interface Registered {
		/**
		 * Triggered when a remote RPC service is fully negotiated and ready to receive calls.
		 *
		 * At this point, [getOrCreateBluetoothClientService] is guaranteed to return a
		 * functional proxy for the specified address.
		 */
		public fun onLapisServiceRegistered(deviceAddress: String)

		/**
		 * Triggered when an RPC service becomes unavailable.
		 *
		 * This occurs if:
		 * 1. The service is manually removed via [unregisterBluetoothServerService].
		 * 2. The underlying [LapisBt] connection to the device is lost.
		 * 3. The [LapisBtRpc] instance is disposed.
		 */
		public fun onLapisServiceUnregistered(deviceAddress: String)
	}
}


public inline fun <reified T : Any> LapisBtRpc.getOrCreateBluetoothClientService(deviceAddress: String): T =
	getOrCreateBluetoothClientService(deviceAddress, T::class)

public inline fun <reified T : Any> LapisBtRpc.unregisterBluetoothClientService(deviceAddress: String) {
	unregisterBluetoothClientService(deviceAddress, T::class)
}

public inline fun <reified T : Any> LapisBtRpc.registerBluetoothServerService(deviceAddress: String, server: T) {
	registerBluetoothServerService(deviceAddress, server, T::class)
}

public inline fun <reified T : Any> LapisBtRpc.unregisterBluetoothServerService(deviceAddress: String) {
	unregisterBluetoothServerService(deviceAddress, T::class)
}
