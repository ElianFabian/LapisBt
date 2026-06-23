package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.model.BluetoothDevice

/**
 * Provides and manages metadata for RPC requests.
 *
 * Metadata is additional information sent alongside an RPC request that is not
 * part of the method arguments. This can be used for tracing, authentication,
 * or custom protocol headers.
 *
 * @param T The type of the metadata object.
 */
public interface LapisMetadataProvider<out T> {

	/**
	 * Creates the metadata object for an outgoing RPC request.
	 *
	 * @param deviceAddress The address of the remote device.
	 * @param requestId The unique ID of the request.
	 * @param serviceName The name of the RPC service.
	 * @param methodName The name of the method being called.
	 * @param arguments The map of arguments being passed to the method.
	 * @return The metadata object to be sent with the request.
	 */
	public suspend fun createMetadataForOutgoingRequest(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		serviceName: String,
		methodName: String,
		arguments: Map<String, Any?>,
	): T

	/**
	 * Serializes the metadata object into a byte array for transmission.
	 */
	public fun serializeMetadata(metadata: @UnsafeVariance T): ByteArray

	/**
	 * Deserializes a byte array back into a metadata object.
	 */
	public fun deserializeMetadata(rawMetadata: ByteArray): T
}


internal object NoOpLapisMetadataProvider : LapisMetadataProvider<Nothing?> {

	override suspend fun createMetadataForOutgoingRequest(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		serviceName: String,
		methodName: String,
		arguments: Map<String, Any?>,
	): Nothing? {
		return null
	}

	override fun serializeMetadata(metadata: Nothing?): ByteArray {
		return ByteArray(0)
	}

	override fun deserializeMetadata(rawMetadata: ByteArray): Nothing? {
		return null
	}
}
