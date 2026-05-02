package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt.app.common.domain.AndroidHelper

class SimpleBluetoothRpcServer(
	private val deviceAddress: String,
	private val androidHelper: AndroidHelper,
) : SimpleBluetoothRpc {

	override suspend fun showToast(message: String) {
		println("$$$ Received string data: $message")
		androidHelper.showToast("Received string data: $message")
	}

	override suspend fun getMyOwnAddress(): String {
		println("$$$ getMyOwnAddress called: returning $deviceAddress")
		return deviceAddress
	}

	override suspend fun openAppSettings() {
		androidHelper.openAppSettings()
	}
}
