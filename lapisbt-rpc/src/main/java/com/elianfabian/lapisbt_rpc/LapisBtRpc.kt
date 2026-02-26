package com.elianfabian.lapisbt_rpc

import kotlin.reflect.KClass

public interface LapisBtRpc {

	public fun <T : Any> getOrCreateBluetoothApiClient(deviceAddress: String, apiInterface: KClass<T>): T

	public fun getBluetoothApiClientByName(deviceAddress: String, apiName: String): Any?

	public fun <T : Any> registerBluetoothApiServer(server: T, apiInterface: KClass<T>)

	public fun getBluetoothApiServerByName(apiName: String): Any?

	public fun unregisterBluetoothApiClient(deviceAddress: String)

	public fun unregisterBluetoothApiServer(server: Any)
}
