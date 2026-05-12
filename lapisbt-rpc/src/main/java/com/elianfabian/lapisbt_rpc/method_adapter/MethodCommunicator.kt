package com.elianfabian.lapisbt_rpc.method_adapter

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisMetadataProvider
import com.elianfabian.lapisbt_rpc.LapisPacketProcessor
import com.elianfabian.lapisbt_rpc.LapisSerializationStrategy
import com.elianfabian.lapisbt_rpc.annotation.LapisMetadata
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisMethodExecutionEnd
import com.elianfabian.lapisbt_rpc.model.RawLapisRequest
import com.elianfabian.lapisbt_rpc.model.RawLapisResponse
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.serializer.MethodExecutionEndSerializer
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.UUID

// TODO: think of a better name
internal interface MethodCommunicator {

	public suspend fun sendRequest(
		requestId: UUID,
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	)

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
	private val deviceAddress: BluetoothDevice.Address,
	private val packetProcessor: LapisPacketProcessor,
	private val serializationStrategy: LapisSerializationStrategy,
	private val metadataProvider: LapisMetadataProvider<Any?>,
) : MethodCommunicator {

	override suspend fun sendRequest(
		requestId: UUID,
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	) {
		val serviceAnnotation = serviceInterface.getAnnotation(LapisRpc::class.java) ?: error("Service interface ${serviceInterface.name} is missing ${LapisRpc::class.simpleName} annotation")
		val serviceName = serviceAnnotation.name

		val methodAnnotation = method.getAnnotation(LapisMethod::class.java) ?: error("Method ${method.name} is missing ${LapisMethod::class.simpleName} annotation")
		val methodName = methodAnnotation.name

		val valueArgs = args.orEmpty().dropLast(1)
		val parametersNames = method.parameterAnnotations.dropLast(1).map { annotations ->
			val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
			paramAnnotation.name
		}

		val argumentsByName = parametersNames.zip(valueArgs).toMap()

		val metadata = metadataProvider.createMetadataForOutgoingRequest(
			deviceAddress = deviceAddress,
			requestId = requestId,
			serviceName = serviceName,
			methodName = methodName,
			arguments = argumentsByName,
		)

		val request = RawLapisRequest(
			requestId = requestId,
			serviceName = serviceName,
			methodName = methodName,
			rawArguments = argumentsByName.mapValues { (_, value) ->
				val byteArrayOutputStream = ByteArrayOutputStream()

				@Suppress("UNCHECKED_CAST")
				val serializer = serializationStrategy.serializerForClass(value?.let { it::class } ?: Nothing::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")
				serializer.serialize(byteArrayOutputStream, value)
				byteArrayOutputStream.toByteArray()
			},
			rawMetadata = metadataProvider.serializeMetadata(metadata),
		)
		// TODO: we should rethink it to see if it actually makes sense
		val methodMetadataAnnotations = method.annotations.filter { it.javaClass.getAnnotation(LapisMetadata::class.java) != null }


		println("$$$$ Sending request with id ${request.requestId}, service: ${request.serviceName}, method: ${request.methodName}, arguments: ${request.rawArguments.keys.joinToString()}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		RequestSerializer.serialize(payloadStream, request)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Request.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations,
		)

		println("$$$$ Finished sending request with id ${request.requestId}")
	}

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
