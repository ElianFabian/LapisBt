package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

class SimpleBluetoothRpcServer(
	private val deviceAddress: String,
) : SimpleBluetoothRpc {

	override suspend fun sendString(data: String) {
		println("$$$ Received string data: $data")
	}

	override suspend fun sendInt(data: Int) {
		println("$$$ Received int data: $data")
	}

	override suspend fun getMyOwnAddress(): String {
		println("$$$ getMyOwnAddress called: returning $deviceAddress")
		return deviceAddress
	}
}
