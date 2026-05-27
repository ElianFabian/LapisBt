package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SimpleBluetoothRpcServer(
	private val deviceAddress: BluetoothDevice.Address,
	private val androidHelper: AndroidHelper,
) : SimpleBluetoothRpc, LapisBtRpc.Registered {

	override suspend fun showToast(message: String) {
		println("$$$ Received string data: $message")
		androidHelper.showToast("Received string data: $message")
	}

	override suspend fun getMyOwnAddress(): String {
		println("$$$ getMyOwnAddress: ${getLapisRequestInfo()}")
		return deviceAddress.value
	}

	override suspend fun openAppSettings() {
		androidHelper.openAppSettings()
	}

	override fun naturalNumbers() = flow {

		println("$$$ naturalNumbers: ${getLapisRequestInfo()}")

		var i = 0
		while (i >= 0) {
			delay(800)
			emit(i++)
		}
	}

	override fun brightnessFlow(): Flow<Int> {
		return androidHelper.brightnessFlow()
	}

	override fun lightSensor(): Flow<Float> {
		return androidHelper.lightSensorFlow()
	}

	override suspend fun sendLargeData(data: Flow<String>) {
		data.collect {
			println("$$$ sendLargeData: $it")
		}
	}

	override fun onLapisServiceRegistered(deviceAddress: BluetoothDevice.Address) {
		println("$$$ onLapisServiceRegistered: $deviceAddress, $this")
	}

	override fun onLapisServiceUnregistered(deviceAddress: BluetoothDevice.Address) {
		println("$$$ onLapisServiceUnregistered: $deviceAddress, $this")
	}
}
