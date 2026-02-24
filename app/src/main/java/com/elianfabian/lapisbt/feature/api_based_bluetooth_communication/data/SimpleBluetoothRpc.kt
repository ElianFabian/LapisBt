package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data

import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam

@LapisRpc(name = "SimpleBluetoothRpc")
interface SimpleBluetoothRpc {

	@LapisMethod("sendString")
	suspend fun sendString(
		@LapisParam("data")
		data: String,
	)

	@LapisMethod("sendInt")
	suspend fun sendInt(
		@LapisParam("data")
		data: Int,
	)

	@LapisMethod("getMyOwnAddress")
	suspend fun getMyOwnAddress(): String
}
