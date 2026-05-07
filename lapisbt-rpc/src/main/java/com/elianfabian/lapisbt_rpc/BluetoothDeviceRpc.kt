package com.elianfabian.lapisbt_rpc

import android.util.Log
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.annotation.LapisMetadata
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.DeviceNotConnectedException
import com.elianfabian.lapisbt_rpc.exception.RemoteException
import com.elianfabian.lapisbt_rpc.method_adapter.LapisMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.LapisServerService
import com.elianfabian.lapisbt_rpc.method_adapter.MethodCommunicatorImpl
import com.elianfabian.lapisbt_rpc.method_adapter.adapter.FlowMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.adapter.SuspendMethodAdapter
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.model.RawLapisRequest
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.serializer.MethodExecutionEndSerializer
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// TODO: we should find a way to encapsulate the logic for different types of functions (suspend, flow, ...)
// TODO: add support for Result type?
// TODO: add support for flows
internal class BluetoothDeviceRpc(
	private val deviceAddress: String,
	private val lapisBt: LapisBt,
	private val lapisRpc: LapisBtRpc,
	private val serializationStrategy: LapisSerializationStrategy,
	private val packetProcessor: LapisPacketProcessor,
	private val interceptor: LapisInterceptor,
	private val metadataProvider: LapisMetadataProvider<Any?>,
) {
	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _pendingClientMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _pendingServerMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _canSendEndByRequestId = ConcurrentHashMap<UUID, MutableStateFlow<Boolean>>()

	private val _returnTypeAdapters = mutableSetOf<LapisMethodAdapter>(
		SuspendMethodAdapter(
			deviceAddress = deviceAddress,
			methodCommunicator = MethodCommunicatorImpl(
				packetProcessor = packetProcessor,
				serializationStrategy = serializationStrategy,
			)
		),
		FlowMethodAdapter(
			deviceAddress = deviceAddress,
			methodCommunicator = MethodCommunicatorImpl(
				packetProcessor = packetProcessor,
				serializationStrategy = serializationStrategy,
			)
		)
	)

	private val _requestSerializer = RequestSerializer
	private val _responseSerializer = ResponseSerializer


	init {
		_scope.launch {
			lapisBt.events.collect { event ->
				when (event) {
					is LapisBt.Event.OnDeviceDisconnected -> {
						if (event.disconnectedDevice.address != deviceAddress) {
							return@collect
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
		_scope.launch(Dispatchers.IO) {
			for (completePacket in packetProcessor.remoteCompletePackets) {
				println("$$$$ Received complete packet with id ${completePacket.packetId}, type: ${completePacket.type}")
				launch {
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
						CompleteBluetoothPacket.Type.MethodExecutionEnd -> {
							processPacketAsMethodExecutionEnd(completePacket)
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

		println("$$$$$ functionCall called with method: ${method.name}, args: ${args?.joinToString()}")

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

		val adapter = _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")

		val requestId = UUID.randomUUID()

		val result = adapter.functionCall(
			requestId = requestId,
			method = method,
			args = args,
		)

		println("$$$ functionCall($requestId) = $requestId")
		_scope.launch {
			_pendingClientMethodByRequestId[requestId] = method
			println("$$$ add pending method($requestId) = $method")

			val metadata = metadataProvider.createMetadataForOutgoingRequest(
				deviceAddress = deviceAddress,
				requestId = requestId,
				serviceName = serviceName,
				methodName = methodName,
				arguments = argumentsByName,
			)

			sendRequest(
				request = RawLapisRequest(
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
				),
				// TODO: we should rethink it to see if it actually makes sense
				methodMetadataAnnotations = method.annotations.filter { it.javaClass.getAnnotation(LapisMetadata::class.java) != null },
			)
		}

		return result
	}

	fun dispose() {
		println("$$$ dispose")
		internalDispose()
	}


	private fun internalDispose() {
		println("$$$ internalDispose")
		packetProcessor.dispose()

		_scope.cancel()

		_returnTypeAdapters.forEach { it.onUnregister() }
		_returnTypeAdapters.clear()
		_pendingClientMethodByRequestId.clear()
		_pendingServerMethodByRequestId.clear()
		_canSendEndByRequestId.clear()
	}


	private suspend fun sendRequest(
		request: RawLapisRequest,
		methodMetadataAnnotations: List<Annotation>,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending request with id ${request.requestId}, service: ${request.serviceName}, method: ${request.methodName}, arguments: ${request.rawArguments.keys.joinToString()}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		_requestSerializer.serialize(payloadStream, request)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Request.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations,
		)

		println("$$$$ Finished sending request with id ${request.requestId}")
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

			val valueType = method.parameterTypes[index]
			val serializer = serializationStrategy.serializerForClass(valueType.kotlin)
			val valueStream = ByteArrayInputStream(valueBytes)

			name to serializer?.deserialize(valueStream)
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
			throw e
		}
		catch (e: Throwable) {
			sendErrorResponse(
				LapisErrorResponse(
					requestId = rawRequest.requestId,
					message = e.stackTraceToString(),
				)
			)
			return
		}
		finally {
			println("$$$ processPacketAsRequest.finally")
			_pendingClientMethodByRequestId.remove(request.requestId)
		}

//		interceptor.interceptIncomingRequestResult(
//			deviceAddress = deviceAddress,
//			request = request,
//			result = result,
//		)
	}

	private suspend fun processPacketAsResponse(completePacket: CompleteBluetoothPacket) {
		val rawResponse = _responseSerializer.deserialize(completePacket.payloadStream)
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
			throwable = RemoteException(message = errorResponse.message),
		)
	}

	private fun processPacketAsCancellation(completePacket: CompleteBluetoothPacket) {
		val cancellation = CancellationSerializer.deserialize(completePacket.payloadStream)

		println("$$$$ process deserialized cancellation for request id ${cancellation.requestId}")

		// TODO: ver por qué se recibe una cancelación y por qué peta cuando en principio en ningún momento se ha borrado el método pendiente
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

	private fun getMethodAdapter(method: Method): LapisMethodAdapter {
		return _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")
	}

	companion object {
		private val TAG = this::class.qualifiedName!!
	}
}
