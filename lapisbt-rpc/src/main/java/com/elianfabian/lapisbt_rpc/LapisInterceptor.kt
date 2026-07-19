package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse

/**
 * An interceptor for RPC communication, allowing for monitoring or modification
 * of requests, responses, and events.
 *
 * Interceptors can be used for logging, metrics, security checks, or
 * transforming data as it flows through the RPC layer.
 */
public interface LapisInterceptor {

	/**
	 * Called when a secure handshake is successfully completed with a remote device.
	 *
	 * This is particularly useful for implementing "Trust On First Use" (TOFU) by
	 * capturing and storing the [remotePublicKeyBytes] for future pinning, or
	 * for generating human-readable "Safety Numbers" from the [sessionKey].
	 */
	public suspend fun interceptHandshakeSuccess(
		deviceAddress: BluetoothDevice.Address,
		remotePublicKeyBytes: ByteArray,
		sharedSecret: ByteArray,
		sessionKey: ByteArray?,
	) {
		// no-op
	}

	/**
	 * Called when an RPC request is received from a remote device.
	 */
	public suspend fun interceptIncomingRequest(
		deviceAddress: BluetoothDevice.Address,
		request: LapisRequest,
	) {
		// no-op
	}

	/**
	 * Called before an RPC request is sent to a remote device.
	 */
	public suspend fun interceptOutgoingRequest(
		deviceAddress: BluetoothDevice.Address,
		request: LapisRequest,
	) {
		// no-op
	}

	/**
	 * Called when an RPC response is received from a remote device.
	 */
	public suspend fun interceptIncomingResponse(
		deviceAddress: BluetoothDevice.Address,
		response: LapisResponse,
	) {
		// no-op
	}

	/**
	 * Called before an RPC response is sent to a remote device.
	 */
	public suspend fun interceptOutgoingResponse(
		deviceAddress: BluetoothDevice.Address,
		response: LapisResponse,
	) {
		// no-op
	}

	/**
	 * Called when a method execution finishes locally for an incoming request.
	 */
	public suspend fun interceptIncomingMethodExecutionEnd(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	/**
	 * Called when a remote method execution finish notification is received for an outgoing request.
	 */
	public suspend fun interceptOutgoingMethodExecutionEnd(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	/**
	 * Called when an error message is received from a remote device.
	 */
	public suspend fun interceptIncomingErrorMessage(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		message: String,
	) {
		// no-op
	}

	/**
	 * Called before an error message is sent to a remote device.
	 */
	public suspend fun interceptOutgoingErrorMessage(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		message: String,
	) {
		// no-op
	}

	/**
	 * Called when a cancellation request is received from a remote device.
	 */
	public suspend fun interceptIncomingCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	/**
	 * Called before a cancellation request is sent to a remote device.
	 */
	public suspend fun interceptOutgoingCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
	) {
		// no-op
	}

	/**
	 * Called when a remote device starts collecting a Flow parameter sent from this device.
	 */
	public suspend fun interceptIncomingFlowParameterCollection(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called when this device starts collecting a Flow parameter from a remote device.
	 */
	public suspend fun interceptOutgoingFlowParameterCollection(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called when a value is emitted for a Flow parameter from a remote device.
	 */
	public suspend fun interceptIncomingFlowParameterEmission(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		// no-op
	}

	/**
	 * Called when a value is emitted for a local Flow parameter to a remote device.
	 */
	public suspend fun interceptOutgoingFlowParameterEmission(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		// no-op
	}

	/**
	 * Called when a Flow parameter cancellation is received from a remote device.
	 */
	public suspend fun interceptIncomingFlowParameterCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called before a Flow parameter cancellation is sent to a remote device.
	 */
	public suspend fun interceptOutgoingFlowParameterCancellation(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called when a Flow parameter completion is received from a remote device.
	 */
	public suspend fun interceptIncomingFlowParameterCompletion(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called before a Flow parameter completion is sent to a remote device.
	 */
	public suspend fun interceptOutgoingFlowParameterCompletion(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
	) {
		// no-op
	}

	/**
	 * Called when a Flow parameter error is received from a remote device.
	 */
	public suspend fun interceptIncomingFlowParameterError(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		flowId: Int,
		parameterName: String,
		message: String,
	) {
		// no-op
	}

	/**
	 * Called before a Flow parameter error is sent to a remote device.
	 */
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
