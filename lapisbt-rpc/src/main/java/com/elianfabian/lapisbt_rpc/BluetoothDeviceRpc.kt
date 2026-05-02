package com.elianfabian.lapisbt_rpc

import android.util.Log
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.DeviceNotConnectedException
import com.elianfabian.lapisbt_rpc.exception.RemoteException
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.model.RawLapisRequest
import com.elianfabian.lapisbt_rpc.model.RawLapisResponse
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.getRawClass
import com.elianfabian.lapisbt_rpc.util.getSuspendReturnType
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import com.elianfabian.lapisbt_rpc.util.isSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine

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
	private val _pendingContinuationsByRequestId = ConcurrentHashMap<UUID, Continuation<Any?>>()
	private val _pendingMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _activeServerJobs = ConcurrentHashMap<UUID, Job>()

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

						internalDispose(disconnected = true)
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
					}
				}
			}
		}
	}


	fun functionCall(
		proxy: Any,
		apiInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	): Any {
		if (method.declaringClass == Any::class.java) {
			return when (method.name) {
				"toString" -> "${apiInterface::class.simpleName}+Proxy@${System.identityHashCode(proxy)}#$deviceAddress"
				"hashCode" -> System.identityHashCode(proxy)
				"equals" -> proxy == args?.get(0)
				else -> method.invoke(this, *args.orEmpty())
			}
		}

		if (lapisBt.getRemoteDevice(deviceAddress).connectionState != BluetoothDevice.ConnectionState.Connected) {
			// TODO: Maybe we could add a configuration so we can choose to whether suspend or throw an exception
			throw DeviceNotConnectedException(deviceAddress)
		}

		println("$$$$$ functionCall called with method: ${method.name}, args: ${args?.joinToString()}")
		val parameterTypes = method.parameterTypes
		val isSuspend = parameterTypes.isNotEmpty() && Continuation::class.java.isAssignableFrom(parameterTypes.last())

		if (!isSuspend) {
			// Later we'll support non-suspended functions too for Flow support
			error("Non-suspended functions aren't yet supported")
		}

		@Suppress("UNCHECKED_CAST")
		val continuation = args.orEmpty().last() as Continuation<Any?>
		return try {
			val apiAnnotation = apiInterface.getAnnotation(LapisRpc::class.java) ?: error("API interface ${apiInterface.name} is missing ${LapisRpc::class.simpleName} annotation")
			val apiName = apiAnnotation.name

			val methodAnnotation = method.getAnnotation(LapisMethod::class.java) ?: error("Method ${method.name} is missing ${LapisMethod::class.simpleName} annotation")
			val methodName = methodAnnotation.name

			val valueArgs = args.orEmpty().dropLast(1)
			val parametersNames = method.parameterAnnotations.dropLast(1).map { annotations ->
				val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
				paramAnnotation.name
			}

			val argumentsByName = parametersNames.zip(valueArgs).toMap()

			val rpcBlock = suspend {
				suspendCancellableCoroutine { cancellableContinuation ->
					val requestId = UUID.randomUUID()

					_pendingContinuationsByRequestId[requestId] = cancellableContinuation
					_pendingMethodByRequestId[requestId] = method

					cancellableContinuation.invokeOnCancellation { cause ->
						println("$$$ invokeOnCompletion($requestId): $cause")
						_pendingContinuationsByRequestId.remove(requestId)
						_pendingMethodByRequestId.remove(requestId)

						if (cause is CancellationException) {
							_scope.launch {
								try {
									sendCancellation(LapisCancellation(requestId = requestId))
								}
								catch (e: Exception) {
									Log.e(TAG, "Failed to send cancellation for $requestId", e)
								}
							}
						}
					}

					_scope.launch {
						try {
							val metadata = metadataProvider.createMetadataForOutgoingRequest(
								deviceAddress = deviceAddress,
								requestId = requestId.toString(),
								apiName = apiName,
								methodName = methodName,
								arguments = argumentsByName,
							)

							val serializedMetadata = metadataProvider.serializeMetadata(metadata)

							val rawRequest = RawLapisRequest(
								requestId = requestId,
								apiName = apiName,
								methodName = methodName,
								rawArguments = argumentsByName.mapValues { (_, value) ->
									val byteArrayOutputStream = ByteArrayOutputStream()

									@Suppress("UNCHECKED_CAST")
									val serializer = serializationStrategy.serializerForClass(value?.let { it::class } ?: Nothing::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")
									serializer.serialize(byteArrayOutputStream, value)
									byteArrayOutputStream.toByteArray()
								},
								rawMetadata = serializedMetadata,
							)

							val request = LapisRequest(
								requestId = requestId,
								apiName = apiName,
								methodName = methodName,
								arguments = argumentsByName,
								metadata = metadata,
							)

							interceptor.interceptOutgoingRequest(deviceAddress = deviceAddress, request = request)

							sendRequest(rawRequest)
						}
						catch (e: Exception) {
							if (cancellableContinuation.isActive) {
								cancellableContinuation.resumeWithException(e)
							}
						}
					}
				}
			}

			rpcBlock.startCoroutine(continuation)
			COROUTINE_SUSPENDED
		}
		catch (t: Throwable) {
			// TODO: I'm not sure if this is the right way, maybe we should use a different dispatcher for resuming with exception?
			Dispatchers.Default.dispatch(continuation.context) {
				continuation.intercepted().resumeWithException(t)
			}
			COROUTINE_SUSPENDED
		}
	}

	fun dispose() {
		internalDispose()
	}


	private fun internalDispose(disconnected: Boolean = false) {
		packetProcessor.dispose()

		_scope.cancel()

		_pendingMethodByRequestId.clear()

		val message = if (disconnected) {
			"BluetoothDeviceRpc for '$deviceAddress' is being disposed because the device got disconnected"
		}
		else "BluetoothDeviceRpc for '$deviceAddress' is being disposed"

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException(message))
		}
		_activeServerJobs.clear()

		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			continuation.resumeWithException(CancellationException(message))
		}
		_pendingContinuationsByRequestId.clear()
	}

	private suspend fun sendRequest(
		request: RawLapisRequest,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending request with id ${request.requestId}, api: ${request.apiName}, method: ${request.methodName}, arguments: ${request.rawArguments.keys.joinToString()}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		_requestSerializer.serialize(payloadStream, request)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Request.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		)

		println("$$$$ Finished sending request with id ${request.requestId}")
	}

	private suspend fun sendResponse(
		response: RawLapisResponse,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending response for request id ${response.requestId}, data: ${response.rawData}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		_responseSerializer.serialize(payloadStream, response)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Response.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		)

		println("$$$$ Finished sending response with id ${response.requestId}")
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
		)

		println("$$$$ Finished sending error response with id ${errorResponse.requestId}")
	}

	private suspend fun sendCancellation(
		cancellation: LapisCancellation,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending cancellation for request id ${cancellation.requestId}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		CancellationSerializer.serialize(payloadStream, cancellation)

		packetProcessor.sendPacketData(
			type = CompleteBluetoothPacket.Type.Cancellation.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		)

		println("$$$$ Finished sending cancellation with id ${cancellation.requestId}")
	}

	private suspend fun processPacketAsRequest(completePacket: CompleteBluetoothPacket) {
		val rawRequest = RequestSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized request with id ${rawRequest.requestId}, api: ${rawRequest.apiName}, method: ${rawRequest.methodName}, arguments: ${rawRequest.rawArguments.keys.joinToString()}")

		val serverImplementation = lapisRpc.getBluetoothServerApiByName(
			deviceAddress = deviceAddress,
			apiName = rawRequest.apiName,
		)

		println("$$$$ Found server implementation for API ${rawRequest.apiName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val apiInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == rawRequest.apiName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = rawRequest.requestId,
				message = "No interface found for API: ${rawRequest.apiName}",
			)
		)

		val method = apiInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			println("$$$$ Checking method ${method.name} with annotation ${annotation?.name} against request method name ${rawRequest.methodName}")
			annotation?.name == rawRequest.methodName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = rawRequest.requestId,
				message = "No method found with name ${rawRequest.methodName} in API ${rawRequest.apiName}",
			)
		)

		// TODO: at the moment all functions are suspended, but later we'll support non-suspended functions too, so we'll have to check if the method is suspended or not and call it accordingly
		if (!method.isSuspend()) {
			sendErrorResponse(
				LapisErrorResponse(
					requestId = rawRequest.requestId,
					message = "The method '${rawRequest.methodName}' in API ${rawRequest.apiName} is not marked as suspend",
				)
			)
			error("Received request for non-suspended method ${method.name}, but only suspended methods are supported at the moment")
		}

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
			val message = "Missing parameters from request ${rawRequest.requestId} for API server ${rawRequest.apiName}, and method ${rawRequest.methodName}: $missingParameters"

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
				Log.w(TAG, "Client parameter '$clientParameterName' not defined in server method '${method.name}' with API name '${rawRequest.apiName}'")
			}
		}

		val currentJob = currentCoroutineContext()[Job]
		if (currentJob != null) {
			_activeServerJobs[rawRequest.requestId] = currentJob
		}

		val metadata = metadataProvider.deserializeMetadata(rawRequest.rawMetadata)

		val request = LapisRequest(
			requestId = rawRequest.requestId,
			apiName = rawRequest.apiName,
			methodName = rawRequest.methodName,
			arguments = orderedArgsByName,
			metadata = metadata,
		)

		val result = try {
			val requestInfo = LapisRequestInfo(
				deviceAddress = deviceAddress,
				request = request,
			)
			withContext(LapisRequestInfoContext(requestInfo)) {
				interceptor.interceptIncomingRequest(deviceAddress = deviceAddress, request = request)
				method.invokeSuspend(serverImplementation, *orderedArgsByName.values.toTypedArray())
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
			_activeServerJobs.remove(rawRequest.requestId)
		}

		interceptor.interceptIncomingRequestResult(
			deviceAddress = deviceAddress,
			request = request,
			result = result,
		)

		println("$$$$ Method ${method.name}, with return type raw class: ${method.returnType.getRawClass()}, with generic return type raw class: ${method.genericReturnType.getRawClass()}, suspend type: ${method.getSuspendReturnType()}, with args: $orderedArgsByName, returned result: $result")

		@Suppress("UNCHECKED_CAST")
		val serializer = serializationStrategy.serializerForClass(if (result == null) Nothing::class else result::class) as? LapisSerializer<Any?> ?: error("No serializer registered for return type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, result)
		val serializedResult = byteArrayOutputStream.toByteArray()

		val rawResponse = RawLapisResponse(
			requestId = rawRequest.requestId,
			rawData = serializedResult,
		)

		val response = LapisResponse(
			requestId = rawRequest.requestId,
			data = result,
		)

		interceptor.interceptOutgoingResponse(deviceAddress = deviceAddress, response = response)

		sendResponse(rawResponse)
	}

	private suspend fun processPacketAsResponse(completePacket: CompleteBluetoothPacket) {
		val rawResponse = _responseSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized response for request id ${rawResponse.requestId}, data: ${rawResponse.rawData}")

		val method = _pendingMethodByRequestId.remove(rawResponse.requestId) ?: error("No pending method found for response id: ${rawResponse.requestId}")
		println("$$$$ Found pending method for response with id ${rawResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val serializer = serializationStrategy.serializerForClass(method.getSuspendReturnType().kotlin) ?: error("No serializer found for return type: ${method.returnType}")

		val deserializedResult = serializer.deserialize(ByteArrayInputStream(rawResponse.rawData))

		val continuation = _pendingContinuationsByRequestId.remove(rawResponse.requestId) ?: error("No pending continuation found for response id: ${rawResponse.requestId}")

		val response = LapisResponse(
			requestId = rawResponse.requestId,
			data = deserializedResult,
		)

		interceptor.interceptIncomingResponse(deviceAddress = deviceAddress, response = response)

		continuation.resume(deserializedResult)
	}

	private fun processPacketAsErrorResponse(completePacket: CompleteBluetoothPacket) {
		val errorResponse = ErrorResponseSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val method = _pendingMethodByRequestId.remove(errorResponse.requestId) ?: error("No pending method found for error response id: ${errorResponse.requestId}")
		println("$$$$ Found pending method for error response with id ${errorResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val continuation = _pendingContinuationsByRequestId.remove(errorResponse.requestId) ?: error("No pending continuation found for error response id: ${errorResponse.requestId}")

		val exception = RemoteException(errorResponse.message)

		continuation.resumeWithException(exception)
	}

	private fun processPacketAsCancellation(completePacket: CompleteBluetoothPacket) {
		val cancellation = CancellationSerializer.deserialize(completePacket.payloadStream)

		println("$$$$ process deserialized cancellation for request id ${cancellation.requestId}")

		_activeServerJobs.remove(cancellation.requestId)?.cancel()
	}


	companion object {
		private val TAG = BluetoothDeviceRpc::class.simpleName!!
	}
}
