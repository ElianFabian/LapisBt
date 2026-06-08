package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import kotlinx.coroutines.flow.Flow

@LapisRpc(name = "SimpleBluetoothRpc")
interface SimpleBluetoothRpc {

	@LapisMethod(name = "showToast")
	suspend fun showToast(
		@LapisParam(name = "message")
		message: String,
	)

	@LapisMethod(name = "getMyOwnAddress")
	suspend fun getMyOwnAddress(): String

	 @LapisMethod(name = "startVibration")
	 suspend fun startVibration()

	 @LapisMethod(name = "stopVibration")
	 suspend fun stopVibration()

	 @LapisMethod(name = "setFlashlight")
	 suspend fun setFlashlight(
		 @LapisParam("enabled")
		 enabled: Boolean,
	 )

	 @LapisMethod("lightSensor")
	 fun lightSensor(): Flow<Float>

	 @LapisMethod("randomNumbers")
	 fun randomNumbers(
		 @LapisParam("intervalMillis")
		 intervalMillis: Long,
	 ): Flow<Int>

	 @LapisMethod("processDataStream")
	 fun processDataStream(
		 @LapisParam("input")
		 input: Flow<Int>,
	 ): Flow<Int>
}
