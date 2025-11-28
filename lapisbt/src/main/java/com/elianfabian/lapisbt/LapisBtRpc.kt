package com.elianfabian.lapisbt

public interface LapisBtRpc {

	public fun <T > getOrCreateBluetoothApiClient(deviceAddress: String): T?

	public fun <T : Any> registerBluetoothApiServer(server: T)

	public fun unregisterBluetoothApiClient(deviceAddress: String)

	public fun unregisterBluetoothApiServer(server: Any)
}
