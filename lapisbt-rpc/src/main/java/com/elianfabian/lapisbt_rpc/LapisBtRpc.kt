package com.elianfabian.lapisbt_rpc

public interface LapisBtRpc {

	public fun <T > getOrCreateBluetoothApiClient(deviceAddress: String, apiInterface: Class<T>): T

	public fun <T : Any> registerBluetoothApiServer(server: T)

	public fun unregisterBluetoothApiClient(deviceAddress: String)

	public fun unregisterBluetoothApiServer(server: Any)
}
