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

	public suspend fun interceptIncomingMethodExecutionEnd(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingMethodExecutionEnd(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	public suspend fun interceptIncomingErrorMessage(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		message: String,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingErrorMessage(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		message: String,
	) {
		// no-op
	}

	public suspend fun interceptIncomingCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	public suspend fun interceptIncomingFlowParameterCollection(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingFlowParameterCollection(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptIncomingFlowParameterEmission(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingFlowParameterEmission(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		// no-op
	}

	public suspend fun interceptIncomingFlowParameterCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingFlowParameterCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptIncomingFlowParameterCompletion(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingFlowParameterCompletion(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	public suspend fun interceptIncomingFlowParameterError(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		message: String,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingFlowParameterError(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		message: String,
	) {
		// no-op
	}
}


internal object NoOpLapisInterceptor : LapisInterceptor
