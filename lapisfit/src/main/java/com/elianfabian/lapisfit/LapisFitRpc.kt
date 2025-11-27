package com.elianfabian.lapisfit

interface LapisFitRpc {

	fun <T > getOrCreateBluetoothApiClient(deviceAddress: String): T?

	fun <T : Any> registerBluetoothApiServer(server: T)

	fun unregisterBluetoothApiClient(deviceAddress: String)

	fun unregisterBluetoothApiServer(server: Any)
}
