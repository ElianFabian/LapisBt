package com.elianfabian.lapisbt_rpc

public interface LapisMetadataProvider<out T> {

	public suspend fun createMetadataForOutgoingRequest(
		deviceAddress: String,
		requestId: String,
		serviceName: String,
		methodName: String,
		arguments: Map<String, Any?>,
	): T

	public fun serializeMetadata(metadata: @UnsafeVariance T): ByteArray

	public fun deserializeMetadata(rawMetadata: ByteArray): T
}


internal object NoOpLapisMetadataProvider : LapisMetadataProvider<Nothing?> {

	override suspend fun createMetadataForOutgoingRequest(
		deviceAddress: String,
		requestId: String,
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
