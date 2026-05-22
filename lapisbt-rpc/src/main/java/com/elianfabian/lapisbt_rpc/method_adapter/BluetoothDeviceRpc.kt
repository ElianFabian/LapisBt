package com.elianfabian.lapisbt_rpc.method_adapter

import android.util.Log
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.LapisInterceptor
import com.elianfabian.lapisbt_rpc.LapisMetadataProvider
import com.elianfabian.lapisbt_rpc.LapisPacketProcessor
import com.elianfabian.lapisbt_rpc.LapisRequestInfo
import com.elianfabian.lapisbt_rpc.LapisRequestInfoContext
import com.elianfabian.lapisbt_rpc.LapisSerializationStrategy
import com.elianfabian.lapisbt_rpc.annotation.LapisMetadata
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.DeviceNotConnectedException
import com.elianfabian.lapisbt_rpc.exception.LapisRemoteException
import com.elianfabian.lapisbt_rpc.exception.LocalException
import com.elianfabian.lapisbt_rpc.exception.RemoteCancellationException
import com.elianfabian.lapisbt_rpc.method_adapter.adapter.FlowMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.adapter.SuspendMethodAdapter
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisArgumentFlowCancellation
import com.elianfabian.lapisbt_rpc.model.LapisArgumentFlowCollection
import com.elianfabian.lapisbt_rpc.model.LapisArgumentFlowCompletion
import com.elianfabian.lapisbt_rpc.model.LapisArgumentFlowError
import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisMethodExecutionEnd
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.model.RawLapisArgumentFlowEmission
import com.elianfabian.lapisbt_rpc.model.RawLapisRequest
import com.elianfabian.lapisbt_rpc.model.RawLapisResponse
import com.elianfabian.lapisbt_rpc.serializer.ArgumentFlowCancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ArgumentFlowCollectionSerializer
import com.elianfabian.lapisbt_rpc.serializer.ArgumentFlowCompletionSerializer
import com.elianfabian.lapisbt_rpc.serializer.ArgumentFlowErrorSerializer
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.FlowArgumentEmissionSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.serializer.MethodExecutionEndSerializer
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.extractFirstGenericArgument
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// TODO: test this
internal class BluetoothDeviceRpc(
	private val deviceAddress: BluetoothDevice.Address,
	private val lapisBt: LapisBt,
	private val lapisRpc: LapisBtRpc,
	private val interceptor: LapisInterceptor,
	private val packetProcessor: LapisPacketProcessor,
	private val serializationStrategy: LapisSerializationStrategy,
	private val metadataProvider: LapisMetadataProvider<Any?>,
) {

	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private var _isDisposed = false

	private val _pendingClientMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _pendingServerMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _canSendEndByRequestId = ConcurrentHashMap<UUID, MutableStateFlow<Boolean>>()
	private val _pendingArgumentFlowChannelsById = ConcurrentHashMap<UUID, SendChannel<Any?>>()
	private val _pendingArgumentFlowById = ConcurrentHashMap<UUID, Flow<Any?>>()
	private val _pendingArgumentFlowJobById = ConcurrentHashMap<UUID, Job>()


	private val _returnTypeAdapters = mutableSetOf(
		SuspendMethodAdapter(
			deviceAddress = deviceAddress,
			bluetoothDeviceRpc = this,
		),
		FlowMethodAdapter(
			deviceAddress = deviceAddress,
			bluetoothDeviceRpc = this,
		)
	)


	init {
		_scope.launch {
			lapisBt.events.collect { event ->
				when (event) {
					is LapisBt.Event.OnDeviceDisconnected -> {
						if (event.disconnectedDevice.address != deviceAddress) {
							return@collect
						}

						_pendingClientMethodByRequestId.forEach { (_, method) ->
							val adapter = getMethodAdapter(method)

							adapter.onDeviceDisconnected(deviceAddress)
						}
						_pendingServerMethodByRequestId.forEach { (_, method) ->
							val adapter = getMethodAdapter(method)

							adapter.onDeviceDisconnected(deviceAddress)
						}

						println("$$$ device disconnected")
						internalDispose()
					}
					else -> Unit
				}
			}
		}
		_scope.launch {
			lapisBt.sendData(deviceAddress) { stream ->
				packetProcessor.sendData(stream)
			}
		}
		_scope.launch {
			lapisBt.receiveData(deviceAddress) { stream ->
				packetProcessor.receiveData(stream)
			}
		}
		_scope.launch {
			for (completePacket in packetProcessor.remoteCompletePackets) {
				println("$$$$ Received complete packet with id ${completePacket.packetId}, type: ${completePacket.type}")
				launch {
					ensureActive()
					when (completePacket.type) {
						CompleteBluetoothPacket.Type.Request -> {
							processPacketAsRequest(completePacket)
						}
						CompleteBluetoothPacket.Type.Response -> {
							processPacketAsResponse(completePacket)
						}
						CompleteBluetoothPacket.Type.ErrorResponse -> {
							processPacketAsErrorResponse(completePacket)
						}
						CompleteBluetoothPacket.Type.Cancellation -> {
							processPacketAsCancellation(completePacket)
						}
						CompleteBluetoothPacket.Type.Completion -> {
							processPacketAsMethodExecutionEnd(completePacket)
						}
						CompleteBluetoothPacket.Type.ArgumentFlowCollection -> {
							processPacketAsArgumentFlowCollection(completePacket)
						}
						CompleteBluetoothPacket.Type.ArgumentFlowEmission -> {
							processPacketAsArgumentFlowEmission(completePacket)
						}
						CompleteBluetoothPacket.Type.ArgumentFlowCancellation -> {
							processPacketAsArgumentFlowCancellation(completePacket)
						}
						CompleteBluetoothPacket.Type.ArgumentFlowCompletion -> {
							processPacketAsArgumentFlowCompletion(completePacket)
						}
						CompleteBluetoothPacket.Type.ArgumentFlowError -> {
							processPacketAsArgumentFlowError(completePacket)
						}
					}
				}
			}
		}
	}

	fun functionCall(
		proxy: Any,
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	): Any {
		println("$$$ functionCall: method: $method, args: ${args.contentToString()}")

		if (method.declaringClass == Any::class.java) {
			return when (method.name) {
				"toString" -> "${serviceInterface::class.simpleName}+Proxy[$deviceAddress]@${System.identityHashCode(proxy)}"
				"hashCode" -> System.identityHashCode(proxy)
				"equals" -> proxy === args?.get(0)
				else -> method.invoke(this, *args.orEmpty())
			}
		}

		if (lapisBt.getRemoteDevice(deviceAddress).connectionState != BluetoothDevice.ConnectionState.Connected) {
			// TODO: Maybe we could add a configuration so we can choose to whether suspend or throw an exception
			throw DeviceNotConnectedException(deviceAddress)
		}

		if (lapisBt.getRemoteDevice(deviceAddress).connectionState != BluetoothDevice.ConnectionState.Connected) {
			// TODO: Maybe we could add a configuration so we can choose to whether suspend or throw an exception
			throw DeviceNotConnectedException(deviceAddress)
		}

		val adapter = _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")

		val result = adapter.functionCall(
			serviceInterface = serviceInterface,
			method = method,
			args = args,
			onGenerateRequestId = { requestId ->
				_pendingClientMethodByRequestId[requestId] = method
			}
		)

		return result
	}

	suspend fun sendRequest(
		requestId: UUID,
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	) {
		val serviceAnnotation = serviceInterface.getAnnotation(LapisRpc::class.java) ?: error("Service interface ${serviceInterface.name} is missing ${LapisRpc::class.simpleName} annotation")
		val serviceName = serviceAnnotation.name

		val methodAnnotation = method.getAnnotation(LapisMethod::class.java) ?: error("Method ${method.name} is missing ${LapisMethod::class.simpleName} annotation")
		val methodName = methodAnnotation.name

		val valueArgs = args.orEmpty().toList()

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
			rawArguments = argumentsByName.mapValues { (parameterName, value) ->
				if (value != null && Flow::class.isInstance(value)) {
					val flowId = UUID.randomUUID()

					_scope.launch {
						val flow = value as? Flow<Any?> ?: error("Only Flow<*> is supported for Flow parameters, but got ${value::class}")
						_pendingArgumentFlowById[flowId] = flow
					}

					val byteArrayOutputStream = ByteArrayOutputStream()
					val dataStream = DataOutputStream(byteArrayOutputStream)
					dataStream.writeLong(flowId.mostSignificantBits)
					dataStream.writeLong(flowId.leastSignificantBits)
					byteArrayOutputStream.toByteArray()
				}
				else {
					val byteArrayOutputStream = ByteArrayOutputStream()

					@Suppress("UNCHECKED_CAST")
					val serializer = serializationStrategy.serializerForClass(value?.let { it::class } ?: Nothing::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")
					serializer.serialize(byteArrayOutputStream, value)
					byteArrayOutputStream.toByteArray()
				}
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

	suspend fun sendResult(
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

	suspend fun sendEnd(requestId: UUID) {
		val methodExecutionEnd = LapisMethodExecutionEnd(
			requestId = requestId,
		)

		val byteArrayStream = ByteArrayOutputStream()
		MethodExecutionEndSerializer.serialize(byteArrayStream, methodExecutionEnd)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Completion.byteValue,
			payload = byteArrayStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	suspend fun sendErrorMessage(
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

	suspend fun cancel(
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

	fun dispose() {
		internalDispose()
	}


	private suspend fun processPacketAsRequest(completePacket: CompleteBluetoothPacket) {
		val rawRequest = RequestSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized request with id ${rawRequest.requestId}, service: ${rawRequest.serviceName}, method: ${rawRequest.methodName}, arguments: ${rawRequest.rawArguments.keys.joinToString()}")

		val serverImplementation = lapisRpc.getBluetoothServerServiceByName(
			deviceAddress = deviceAddress,
			serviceName = rawRequest.serviceName,
		)

		println("$$$$ Found server implementation for service ${rawRequest.serviceName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val serviceInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == rawRequest.serviceName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = rawRequest.requestId,
				message = "No interface found for service: ${rawRequest.serviceName}",
			)
		)

		// TODO: we could create a map for fast method lookup
		val method = serviceInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			println("$$$$ Checking method ${method.name} with annotation ${annotation?.name} against request method name ${rawRequest.methodName}")
			annotation?.name == rawRequest.methodName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = rawRequest.requestId,
				message = "No method found with name ${rawRequest.methodName} in service ${rawRequest.serviceName}",
			)
		)

		_pendingServerMethodByRequestId[rawRequest.requestId] = method

		val parametersNames = method.parameterAnnotations.dropLast(1).map { annotations ->
			val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
			paramAnnotation.name
		}

		val missingParameters = mutableListOf<String>()
		val orderedArgsByName = parametersNames.mapIndexedNotNull { index, name ->
			val valueBytes = rawRequest.rawArguments[name]
			if (valueBytes == null) {
				missingParameters.add(name)
				return@mapIndexedNotNull null
			}

			val valueType = method.parameterTypes[index].kotlin
			if (valueType == Flow::class) {
				val flowId = ByteArrayInputStream(valueBytes).use { stream ->
					val dataStream = DataInputStream(stream)
					val mostSigBits = dataStream.readLong()
					val leastSigBits = dataStream.readLong()
					UUID(mostSigBits, leastSigBits)
				}

				println("$$$ Creating flow for parameter '$name' of method '${method.name}' with flow id $flowId")

				val flow = callbackFlow {
					_pendingArgumentFlowChannelsById[flowId] = this

					sendArgumentFlowCollection(
						flowId = flowId,
						parameterName = name,
						requestId = rawRequest.requestId,
					)

					awaitClose {
						println("$$$ Flow for parameter '$name' of method '${method.name}' is being cancelled locally")

						_pendingArgumentFlowChannelsById.remove(flowId)

						launch {
							sendArgumentFlowCancellation(
								flowId = flowId,
								parameterName = name,
								requestId = rawRequest.requestId,
							)
						}
					}
				}.shareIn(
					scope = _scope,
					started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
					replay = 0,
				)

				name to flow
			}
			else {
				val serializer = serializationStrategy.serializerForClass(valueType)
				val valueStream = ByteArrayInputStream(valueBytes)

				name to serializer?.deserialize(valueStream)
			}
		}.toMap()

		if (missingParameters.isNotEmpty()) {
			val message = "Missing parameters from request ${rawRequest.requestId} for service ${rawRequest.serviceName}, and method ${rawRequest.methodName}: $missingParameters"

			sendErrorResponse(
				LapisErrorResponse(
					requestId = rawRequest.requestId,
					message = message,
				)
			)

			Log.w(TAG, message)
			return
		}

		rawRequest.rawArguments.keys.forEach { clientParameterName ->
			if (clientParameterName !in parametersNames) {
				Log.w(TAG, "Client parameter '$clientParameterName' not defined in server method '${method.name}' with service name '${rawRequest.serviceName}'")
			}
		}

		val metadata = metadataProvider.deserializeMetadata(rawRequest.rawMetadata)

		val request = LapisRequest(
			requestId = rawRequest.requestId,
			serviceName = rawRequest.serviceName,
			methodName = rawRequest.methodName,
			arguments = orderedArgsByName,
			metadata = metadata,
		)

		val adapter = _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")

		try {
			val requestInfo = LapisRequestInfo(
				deviceAddress = deviceAddress,
				request = request,
			)
			withContext(LapisRequestInfoContext(requestInfo)) {
				interceptor.interceptIncomingRequest(deviceAddress = deviceAddress, request = request)

				adapter.onReceiveRequest(
					request = request,
					server = object : LapisServerService {
						override fun invokeMethod(): Any? {
							return method.invoke(serverImplementation, *orderedArgsByName.values.toTypedArray())
						}

						override suspend fun invokeSuspendMethod(): Any? {
							return method.invokeSuspend(serverImplementation, *orderedArgsByName.values.toTypedArray())
						}
					}
				)
			}
		}
		catch (e: CancellationException) {
			println("$$$ Request with id ${request.requestId} was cancelled: ${e.message}")
			throw e
		}
		catch (e: Throwable) {
			println("$$$ Error processing request with id ${request.requestId}: ${e.message}")
			sendErrorResponse(
				LapisErrorResponse(
					requestId = rawRequest.requestId,
					message = e.stackTraceToString(),
				)
			)
			return
		}
		finally {
			println("$$$ processPacketAsRequest.finally: ${request.requestId}")
			_pendingServerMethodByRequestId.remove(request.requestId)
		}

//		interceptor.interceptIncomingRequestResult(
//			deviceAddress = deviceAddress,
//			request = request,
//			result = result,
//		)
	}

	private suspend fun processPacketAsResponse(completePacket: CompleteBluetoothPacket) {
		val rawResponse = ResponseSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized response for request id ${rawResponse.requestId}, data: ${rawResponse.rawData}")

		// We need to make sure the response is fully processed before processing the method execution end
		// I'm not sure if this is the best solution, but this should work for now
		val canProcessEnd = _canSendEndByRequestId.getOrPut(rawResponse.requestId) {
			MutableStateFlow(false)
		}

		val method = _pendingClientMethodByRequestId[rawResponse.requestId] ?: error("No pending method found for response id: ${rawResponse.requestId}")

		val adapter = getMethodAdapter(method)

		println("$$$$ Found pending method for response with id ${rawResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val outputType = adapter.getOutputType(method)

		val serializer = serializationStrategy.serializerForClass(outputType) ?: error("No serializer found for return type: ${method.returnType.kotlin}")

		val deserializedResult = serializer.deserialize(ByteArrayInputStream(rawResponse.rawData))

		val response = LapisResponse(
			requestId = rawResponse.requestId,
			data = deserializedResult,
		)

		adapter.onResult(
			requestId = rawResponse.requestId,
			result = deserializedResult,
		)

		interceptor.interceptIncomingResponse(deviceAddress = deviceAddress, response = response)

		canProcessEnd.value = true
	}

	private fun processPacketAsErrorResponse(completePacket: CompleteBluetoothPacket) {
		val errorResponse = ErrorResponseSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val method = _pendingClientMethodByRequestId.remove(errorResponse.requestId) ?: error("No pending method found for error response id: ${errorResponse.requestId}")
		println("$$$$ Found pending method for error response with id ${errorResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val adapter = getMethodAdapter(method)

		adapter.onErrorMessage(
			requestId = errorResponse.requestId,
			throwable = LapisRemoteException(message = errorResponse.message),
		)
	}

	private fun processPacketAsCancellation(completePacket: CompleteBluetoothPacket) {
		val cancellation = CancellationSerializer.deserialize(completePacket.payloadStream)

		println("$$$$ process deserialized cancellation for request id ${cancellation.requestId}")

		val method = _pendingServerMethodByRequestId.remove(cancellation.requestId) ?: error("No pending method found for cancellation id: ${cancellation.requestId}")

		println("$$$$ process deserialized cancellation for request id ${cancellation.requestId}: $method")

		val adapter = getMethodAdapter(method)

		adapter.onCancel(requestId = cancellation.requestId)
	}

	private suspend fun processPacketAsMethodExecutionEnd(completePacket: CompleteBluetoothPacket) {
		val methodExecutionEnd = MethodExecutionEndSerializer.deserialize(completePacket.payloadStream)

		println("$$$$ process deserialized method execution end for request id ${methodExecutionEnd.requestId}")

		val canProcessEndFlow = _canSendEndByRequestId[methodExecutionEnd.requestId] ?: error("Couldn't check whether we should process the method execution end: ${methodExecutionEnd.requestId}")
		canProcessEndFlow.first { canProcessEnd -> canProcessEnd }

		_canSendEndByRequestId.remove(methodExecutionEnd.requestId)

		val method = _pendingClientMethodByRequestId.remove(methodExecutionEnd.requestId) ?: error("No pending method found for method execution end id: ${methodExecutionEnd.requestId}")

		val adapter = getMethodAdapter(method)

		adapter.onEnd(requestId = methodExecutionEnd.requestId)
	}

	private fun processPacketAsArgumentFlowCollection(completePacket: CompleteBluetoothPacket) {
		val flowCollection = ArgumentFlowCollectionSerializer.deserialize(completePacket.payloadStream)

		// TODO: Maybe we should add the service name and the method name to provide more informative error messages
		val flow = _pendingArgumentFlowById.remove(flowCollection.flowId) ?: error("No pending flow found for argument flow collection for parameter '${flowCollection.parameterName}' with id: ${flowCollection.flowId}")
		_pendingArgumentFlowJobById[flowCollection.flowId] = _scope.launch {
			try {
				flow.collect {
					sendArgumentFlowEmission(
						argumentFlowId = flowCollection.flowId,
						parameterName = flowCollection.parameterName,
						requestId = flowCollection.requestId,
						value = it,
					)
				}
				sendArgumentFlowCompletion(
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName,
					requestId = flowCollection.requestId,
				)
			}
			catch (e: RemoteCancellationException) {
				// no-op
			}
			catch (e: CancellationException) {
				println("$$$ Flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId} was cancelled locally")
				sendArgumentFlowCancellation(
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName,
					requestId = flowCollection.requestId,
				)
			}
			catch (e: LocalException) {
				throw e.cause!!
			}
			catch (e: Throwable) {
				println("$$$ Error collecting flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId}: ${e.message}")
				sendArgumentFlowError(
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName,
					requestId = flowCollection.requestId,
					message = e.message ?: "Unknown error",
				)
			}
			finally {
				_pendingArgumentFlowJobById.remove(flowCollection.flowId)
			}
		}
	}

	private fun processPacketAsArgumentFlowEmission(completePacket: CompleteBluetoothPacket) {
		val emission = FlowArgumentEmissionSerializer.deserialize(completePacket.payloadStream)

		println("$$$$ process deserialized argument flow emission for flow id ${emission.flowId}, value: ${emission.rawValue}")

		val channel = _pendingArgumentFlowChannelsById[emission.flowId] ?: error("No pending flow found for emission with id: ${emission.flowId}")

		val method = _pendingServerMethodByRequestId[emission.requestId] ?: error("No pending method found for argument flow emission with id: ${emission.flowId}")
		println("$$$$ Found pending method for argument flow emission with id ${emission.flowId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		var parameterIndex = -1
		for (parameterAnnotations in method.parameterAnnotations) {
			parameterIndex++

			val paramAnnotation = parameterAnnotations.filterIsInstance<LapisParam>().firstOrNull { it.name == emission.parameterName }
			println("$$$ parameter index: $parameterIndex, parameter name: ${paramAnnotation?.name}, emission parameter name: ${emission.parameterName}")
			if (paramAnnotation != null) {
				break
			}
		}

		println("$$$$ parameter index for emission with id ${emission.flowId} is $parameterIndex")

		if (parameterIndex == -1) {
			error("No parameter found with name '${emission.parameterName}' in method '${method.name}'")
		}

		if (!Flow::class.java.isAssignableFrom(method.parameterTypes[parameterIndex])) {
			error("Parameter with name '${emission.parameterName}' in method '${method.name}' was expected to be a Flow, but got ${method.parameterTypes[parameterIndex]}")
		}

		val flowGenericType = method.genericParameterTypes[parameterIndex].extractFirstGenericArgument().kotlin

		val serializer = serializationStrategy.serializerForClass(flowGenericType) ?: error("No serializer found for argument flow emission for value type $flowGenericType with id: ${emission.flowId}")
		val deserializedValue = serializer.deserialize(ByteArrayInputStream(emission.rawValue))

		channel.trySend(deserializedValue)
	}

	private fun processPacketAsArgumentFlowCancellation(completePacket: CompleteBluetoothPacket) {
		val cancellation = ArgumentFlowCancellationSerializer.deserialize(completePacket.payloadStream)
		println("$$$ process deserialized argument flow cancellation for flow id ${cancellation.flowId}")

		_pendingArgumentFlowJobById.remove(cancellation.flowId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
		_pendingArgumentFlowById.remove(cancellation.flowId)
	}

	private fun processPacketAsArgumentFlowCompletion(completePacket: CompleteBluetoothPacket) {
		val completion = ArgumentFlowCompletionSerializer.deserialize(completePacket.payloadStream)
		println("$$$ process deserialized argument flow completion for flow id ${completion.flowId}")

		_pendingArgumentFlowChannelsById.remove(completion.flowId)?.close()
	}

	private fun processPacketAsArgumentFlowError(completePacket: CompleteBluetoothPacket) {
		val error = ArgumentFlowErrorSerializer.deserialize(completePacket.payloadStream)
		println("$$$ process deserialized argument flow error for flow id ${error.flowId}, message: ${error.message}")

		_pendingArgumentFlowChannelsById.remove(error.flowId)?.close(LapisRemoteException(error.message))
	}

	private suspend fun sendErrorResponse(
		errorResponse: LapisErrorResponse,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ErrorResponseSerializer.serialize(payloadStream, errorResponse)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ErrorResponse.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)

		println("$$$$ Finished sending error response with id ${errorResponse.requestId}")
	}

	private suspend fun sendArgumentFlowCollection(
		flowId: UUID,
		parameterName: String,
		requestId: UUID,
	) {
		println("$$$$ sendArgumentFlowCollection for flow $flowId")

		val flowCollection = LapisArgumentFlowCollection(
			flowId = flowId,
			parameterName = parameterName,
			requestId = requestId,
		)

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ArgumentFlowCollectionSerializer.serialize(payloadStream, flowCollection)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ArgumentFlowCollection.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	private suspend fun sendArgumentFlowCompletion(
		flowId: UUID,
		parameterName: String,
		requestId: UUID,
	) {
		println("$$$$ sendArgumentFlowCompletion for flow $flowId")

		val flowCompletion = LapisArgumentFlowCompletion(
			flowId = flowId,
			parameterName = parameterName,
			requestId = requestId,
		)

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ArgumentFlowCompletionSerializer.serialize(payloadStream, flowCompletion)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ArgumentFlowCompletion.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	private suspend fun sendArgumentFlowCancellation(
		flowId: UUID,
		parameterName: String,
		requestId: UUID,
	) {
		println("$$$$ sendArgumentFlowCancellation for flow $flowId")

		val flowCancellation = LapisArgumentFlowCancellation(
			flowId = flowId,
			parameterName = parameterName,
			requestId = requestId,
		)

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ArgumentFlowCancellationSerializer.serialize(payloadStream, flowCancellation)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ArgumentFlowCancellation.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	private suspend fun sendArgumentFlowError(
		flowId: UUID,
		parameterName: String,
		requestId: UUID,
		message: String,
	) {
		println("$$$$ sendArgumentFlowError for flow $flowId, message: $message")

		val flowError = LapisArgumentFlowError(
			flowId = flowId,
			parameterName = parameterName,
			requestId = requestId,
			message = message,
		)

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ArgumentFlowErrorSerializer.serialize(payloadStream, flowError)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.ArgumentFlowError.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = emptyList(),
		)
	}

	private fun sendArgumentFlowEmission(
		argumentFlowId: UUID,
		parameterName: String,
		requestId: UUID,
		value: Any?,
	) {
		println("$$$$ sendArgumentEmission for flow $argumentFlowId, value: $value")

		@Suppress("UNCHECKED_CAST")
		val serializer = serializationStrategy.serializerForClass(if (value == null) Nothing::class else value::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, value)
		val serializedValue = byteArrayOutputStream.toByteArray()

		val emission = RawLapisArgumentFlowEmission(
			flowId = argumentFlowId,
			requestId = requestId,
			parameterName = parameterName,
			rawValue = serializedValue,
		)

		val emissionBao = ByteArrayOutputStream()

		FlowArgumentEmissionSerializer.serialize(emissionBao, emission)

		val payload = emissionBao.toByteArray()

		_scope.launch {
			packetProcessor.sendPacketData(
				type = CompleteBluetoothPacket.Type.ArgumentFlowEmission.byteValue,
				payload = payload,
				methodMetadataAnnotations = emptyList(),
			)
		}
	}

	private fun getMethodAdapter(method: Method): LapisMethodAdapter {
		return _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")
	}

	private fun internalDispose() {
		if (_isDisposed) {
			return
		}
		_isDisposed = true

		println("$$$ internalDispose")
		packetProcessor.dispose()

		_scope.cancel()

		_returnTypeAdapters.forEach { it.onUnregister() }
		_returnTypeAdapters.clear()
		_pendingClientMethodByRequestId.clear()
		_pendingServerMethodByRequestId.clear()
		_pendingArgumentFlowChannelsById.clear()
		_pendingArgumentFlowById.clear()
		_pendingArgumentFlowJobById.clear()
		_canSendEndByRequestId.clear()
	}

	companion object {
		private val TAG = this::class.qualifiedName!!
	}
}
