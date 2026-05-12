package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse

public interface LapisInterceptor {

	public suspend fun interceptIncomingRequest(
		deviceAddress: BluetoothDevice.Address,
		request: LapisRequest,
	) {
		// no-op
	}

	public suspend fun interceptIncomingRequestResult(
		deviceAddress: BluetoothDevice.Address,
		request: LapisRequest,
		result: Any?,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingRequest(
		deviceAddress: BluetoothDevice.Address,
		request: LapisRequest,
	) {
		// no-op
	}

	public suspend fun interceptIncomingResponse(
		deviceAddress: BluetoothDevice.Address,
		response: LapisResponse,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingResponse(
		deviceAddress: BluetoothDevice.Address,
		response: LapisResponse,
	) {
		// no-op
	}
}


internal object NoOpLapisInterceptor : LapisInterceptor
