package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import kotlin.reflect.KClass

public interface LapisBtRpc {

	public fun <T : Any> getOrCreateBluetoothClientApi(deviceAddress: String, apiInterface: KClass<T>): T

	public fun getBluetoothClientApiByName(deviceAddress: String, apiName: String): Any

	public fun <T : Any> unregisterBluetoothClientApi(deviceAddress: String, apiInterface: KClass<T>)

	public fun <T : Any> unregisterBluetoothApiClientsByAddress(deviceAddress: String)

	public fun <T : Any> registerBluetoothServerApi(deviceAddress: String, server: T, apiInterface: KClass<T>)

	public fun getBluetoothServerApiByName(deviceAddress: String, apiName: String): Any

	public fun <T : Any> unregisterBluetoothServerApi(deviceAddress: String, apiInterface: KClass<T>)

	public fun <T : Any> unregisterBluetoothServerApisByAddress(deviceAddress: String)

	public companion object {

		public fun newInstance(
			lapisBt: LapisBt,
			serializationStrategy: LapisSerializationStrategy? = null
		): LapisBtRpc {
			return LapisBtRpcImpl(
				lapisBt = lapisBt,
				serializationStrategy = serializationStrategy?.withDefaultFallback() ?: DefaultSerializationStrategy
			)
		}
	}
}


public inline fun <reified T : Any> LapisBtRpc.getOrCreateBluetoothClientApi(deviceAddress: String) {
	getOrCreateBluetoothClientApi(deviceAddress, T::class)
}

public inline fun <reified T : Any> LapisBtRpc.unregisterBluetoothClientApi(deviceAddress: String) {
	unregisterBluetoothClientApi(deviceAddress, T::class)
}

public inline fun <reified T : Any> LapisBtRpc.registerBluetoothServerApi(deviceAddress: String, server: T) {
	registerBluetoothServerApi(deviceAddress, server, T::class)
}

public inline fun <reified T : Any> LapisBtRpc.unregisterBluetoothServerApi(deviceAddress: String) {
	unregisterBluetoothServerApi(deviceAddress, T::class)
}
