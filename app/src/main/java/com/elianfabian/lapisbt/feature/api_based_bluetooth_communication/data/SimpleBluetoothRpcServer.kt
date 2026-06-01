package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

class SimpleBluetoothRpcServer(
	private val androidHelper: AndroidHelper,
) : SimpleBluetoothRpc, LapisBtRpc.Registered {

	override suspend fun showToast(message: String) {
		println("$$$ Received showToast: $message")
		androidHelper.showToast(message)
	}

	override suspend fun getMyOwnAddress(): String {
		println("$$$ getMyOwnAddress: ${getLapisRequestInfo()}")
		return getLapisRequestInfo().deviceAddress.value
	}

	override suspend fun startVibration() {
		println("$$$ startVibration")
		androidHelper.startVibration()
	}

	override suspend fun stopVibration() {
		println("$$$ stopVibration")
		androidHelper.stopVibration()
	}

	override suspend fun setFlashlight(enabled: Boolean) {
		println("$$$ setFlashlight: $enabled")
		androidHelper.setFlashlight(enabled)
	}

	override fun lightSensor(): Flow<Float> {
		println("$$$ lightSensor requesting")
		return androidHelper.lightSensorFlow()
	}

	override fun randomNumbers(intervalMillis: Long): Flow<Int> = flow {
		println("$$$ randomNumbers requesting: $intervalMillis")
		while (true) {
			emit(Random.nextInt())
			delay(intervalMillis)
		}
	}

	override fun processDataStream(input: Flow<Int>): Flow<Int> {
		println("$$$ processDataStream requesting")
		return input.map { it * 2 }
	}

	override fun onLapisServiceRegistered(deviceAddress: BluetoothDevice.Address) {
		println("$$$ onLapisServiceRegistered: $deviceAddress, $this")
	}

	override fun onLapisServiceUnregistered(deviceAddress: BluetoothDevice.Address) {
		println("$$$ onLapisServiceUnregistered: $deviceAddress, $this")
	}
}
