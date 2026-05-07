package com.elianfabian.lapisbt_rpc.method_adapter

import com.elianfabian.lapisbt_rpc.LapisPacketProcessor
import com.elianfabian.lapisbt_rpc.LapisSerializationStrategy
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisMethodExecutionEnd
import com.elianfabian.lapisbt_rpc.model.RawLapisResponse
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.serializer.MethodExecutionEndSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import java.io.ByteArrayOutputStream
import java.util.UUID

// TODO: think of a better name
internal interface MethodCommunicator {

	public suspend fun sendResult(
		requestId: UUID,
		result: Any?,
	)

	// TODO: maybe we should somehow force not to be able to call this function more than once
	public suspend fun sendEnd(requestId: UUID)

	// TODO: maybe we should somehow force not to be able to call this function more than once
	public suspend fun sendErrorMessage(
		requestId: UUID,
		message: String,
	)

	// TODO: maybe we should somehow force not to be able to call this function more than once
	public suspend fun cancel(requestId: UUID)
}

internal class MethodCommunicatorImpl(
	private val packetProcessor: LapisPacketProcessor,
	private val serializationStrategy: LapisSerializationStrategy,
) : MethodCommunicator {

	override suspend fun sendResult(
		requestId: UUID,
		result: Any?,
	) {
		@Suppress("UNCHECKED_CAST")
		val serializer = serializationStrategy.serializerForClass(if (result == null) Nothing::class else result::class) as? LapisSerializer<Any?> ?: error("No serializer registered for return type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, result)
		val serializedResult = byteArrayOutputStream.toByteArray()

		val rawResponse = RawLapisResponse(
			requestId = requestId,
			rawData = serializedResult,
		)

		val byteArrayStream = ByteArrayOutputStream()
		ResponseSerializer.serialize(byteArrayStream, rawResponse)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Response.byteValue,
			payload = byteArrayStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	override suspend fun sendEnd(requestId: UUID) {
		val methodExecutionEnd = LapisMethodExecutionEnd(
			requestId = requestId,
		)

		val byteArrayStream = ByteArrayOutputStream()
		MethodExecutionEndSerializer.serialize(byteArrayStream, methodExecutionEnd)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.MethodExecutionEnd.byteValue,
			payload = byteArrayStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	override suspend fun sendErrorMessage(
		requestId: UUID,
		message: String,
	) {
		println("$$$ sendErrorMessage($requestId) = $message")
		val errorResponse = LapisErrorResponse(
			requestId = requestId,
			message = message,
		)

		val byteArrayStream = ByteArrayOutputStream()
		ErrorResponseSerializer.serialize(byteArrayStream, errorResponse)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ErrorResponse.byteValue,
			payload = byteArrayStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	override suspend fun cancel(
		requestId: UUID,
	) {
		println("$$$$ cancel: $requestId")
		val cancellation = LapisCancellation(requestId = requestId)

		val byteArrayStream = ByteArrayOutputStream()
		CancellationSerializer.serialize(byteArrayStream, cancellation)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Cancellation.byteValue,
			payload = byteArrayStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}
}
