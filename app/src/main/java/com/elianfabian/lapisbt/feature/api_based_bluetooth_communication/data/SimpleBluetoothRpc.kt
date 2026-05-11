package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
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

	 @LapisMethod(name = "openAppSettings")
	 suspend fun openAppSettings()

	 @LapisMethod("naturalNumbers")
	 fun naturalNumbers(): Flow<Int>

	 @LapisMethod("brightnessFlow")
	 fun brightnessFlow(): Flow<Int>

	 @LapisMethod("lightSensor")
	 fun lightSensor(): Flow<Float>
}
