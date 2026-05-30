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
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.model.LapisRpcPacket
import com.elianfabian.lapisbt_rpc.serializer.LapisPacketSerializer
import com.elianfabian.lapisbt_rpc.serializer.LapisSerializer
import com.elianfabian.lapisbt_rpc.util.extractFirstGenericArgument
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

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

	private val _pendingClientMethodByRequestId = ConcurrentHashMap<Int, Method>()
	private val _pendingServerMethodByRequestId = ConcurrentHashMap<Int, Method>()
	// Force to fully process the response before being able to process the method execution end
	private val _responseProcessingGates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
	private val _pendingFlowParameterChannelsById = ConcurrentHashMap<Int, SendChannel<Any?>>()
	private val _pendingFlowParameterById = ConcurrentHashMap<Int, Flow<Any?>>()
	private val _pendingFlowParameterJobById = ConcurrentHashMap<Int, Job>()
	private val _nextId = AtomicInteger(0)


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
		println("$$$ bluetoothDeviceRpc: $deviceAddress")
		_scope.launch {
			lapisBt.events.collect { event ->
				when (event) {
					is LapisBt.Event.OnDeviceDisconnected -> {
						if (event.device.address != deviceAddress) {
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

						println("$$$ BluetoothDeviceRpc - device disconnected($deviceAddress): ${hashCode()}")
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
					val packet = LapisPacketSerializer.deserialize(completePacket.type, completePacket.payloadStream)
					processPacket(packet)
				}
			}
		}
	}

	private suspend fun processPacket(packet: LapisRpcPacket) {
		when (packet) {
			is LapisRpcPacket.Request -> processRequest(packet)
			is LapisRpcPacket.Response -> processResponse(packet)
			is LapisRpcPacket.ErrorResponse -> processErrorResponse(packet)
			is LapisRpcPacket.Cancellation -> processCancellation(packet)
			is LapisRpcPacket.Completion -> processMethodExecutionEnd(packet)
			is LapisRpcPacket.FlowParameter.Collection -> processFlowParameterCollection(packet)
			is LapisRpcPacket.FlowParameter.Emission -> processFlowParameterEmission(packet)
			is LapisRpcPacket.FlowParameter.Cancellation -> processFlowParameterCancellation(packet)
			is LapisRpcPacket.FlowParameter.Completion -> processFlowParameterCompletion(packet)
			is LapisRpcPacket.FlowParameter.Error -> processFlowParameterError(packet)
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
		requestId: Int,
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

		val request = LapisRpcPacket.Request(
			requestId = requestId,
			serviceName = serviceName,
			methodName = methodName,
			rawArguments = argumentsByName.mapValues { (parameterName, value) ->
				if (value != null && Flow::class.isInstance(value)) {
					val flowId = generateId()

					_scope.launch {
						val flow = value as? Flow<Any?> ?: error("Only Flow<*> is supported for Flow parameters, but got ${value::class}")
						_pendingFlowParameterById[flowId] = flow
					}

					val byteArrayOutputStream = ByteArrayOutputStream()
					val dataStream = DataOutputStream(byteArrayOutputStream)
					dataStream.writeInt(flowId)
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

		sendPacket(request, methodMetadataAnnotations)

		println("$$$$ Finished sending request with id ${request.requestId}")
	}

	suspend fun sendResult(
		requestId: Int,
		result: Any?,
	) {
		@Suppress("UNCHECKED_CAST")
		val serializer = serializationStrategy.serializerForClass(if (result == null) Nothing::class else result::class) as? LapisSerializer<Any?> ?: error("No serializer registered for return type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, result)
		val serializedResult = byteArrayOutputStream.toByteArray()

		val response = LapisRpcPacket.Response(
			requestId = requestId,
			rawData = serializedResult,
		)

		sendPacket(response)
	}

	suspend fun sendEnd(requestId: Int) {
		sendPacket(LapisRpcPacket.Completion(requestId))
	}

	suspend fun sendErrorMessage(
		requestId: Int,
		message: String,
	) {
		println("$$$ sendErrorMessage($requestId) = $message")
		sendPacket(LapisRpcPacket.ErrorResponse(requestId, message))
	}

	suspend fun cancel(
		requestId: Int,
	) {
		println("$$$$ cancel: $requestId")
		sendPacket(LapisRpcPacket.Cancellation(requestId))
	}

	fun dispose() {
		internalDispose()
	}


	private suspend fun processRequest(rawRequest: LapisRpcPacket.Request) {
		println("$$$$ process request with id ${rawRequest.requestId}, service: ${rawRequest.serviceName}, method: ${rawRequest.methodName}, arguments: ${rawRequest.rawArguments.keys.joinToString()}")

		val serverImplementation = lapisRpc.getBluetoothServerServiceByName(
			deviceAddress = deviceAddress,
			serviceName = rawRequest.serviceName,
		)

		println("$$$$ Found server implementation for service ${rawRequest.serviceName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val serviceInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == rawRequest.serviceName
		} ?: run {
			val message = "No interface found for service: ${rawRequest.serviceName}"
			return withContext(Dispatchers.IO) {
				println("$$$$ Sending error response for request id ${rawRequest.requestId}, message: $message")
				this@BluetoothDeviceRpc.sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				println("$$$$ Finished sending error response with id ${rawRequest.requestId}")
			}
		}

		// TODO: we could create a map for fast method lookup
		val method = serviceInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			println("$$$$ Checking method ${method.name} with annotation ${annotation?.name} against request method name ${rawRequest.methodName}")
			annotation?.name == rawRequest.methodName
		} ?: run {
			val message = "No method found with name ${rawRequest.methodName} in service ${rawRequest.serviceName}"
			return withContext(Dispatchers.IO) {
				println("$$$$ Sending error response for request id ${rawRequest.requestId}, message: $message")
				this@BluetoothDeviceRpc.sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				println("$$$$ Finished sending error response with id ${rawRequest.requestId}")
			}
		}

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
					dataStream.readInt()
				}

				println("$$$ Creating flow for parameter '$name' of method '${method.name}' with flow id $flowId")

				val flow = callbackFlow {
					_pendingFlowParameterChannelsById[flowId] = this

					println("$$$$ sendFlowParameterCollection for flow $flowId")
					sendPacket(LapisRpcPacket.FlowParameter.Collection(requestId = rawRequest.requestId, flowId = flowId, parameterName = name))

					awaitClose {
						println("$$$ Flow for parameter '$name' of method '${method.name}' is being cancelled locally")

						_pendingFlowParameterChannelsById.remove(flowId)

						launch {
							println("$$$$ sendFlowParameterCancellation for flow $flowId")
							sendPacket(
								LapisRpcPacket.FlowParameter.Cancellation(
									requestId = rawRequest.requestId,
									flowId = flowId,
									parameterName = name,
								)
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

			withContext(Dispatchers.IO) {
				println("$$$$ Sending error response for request id ${rawRequest.requestId}, message: $message")
				sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				println("$$$$ Finished sending error response with id ${rawRequest.requestId}")
			}

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
			val message = e.stackTraceToString()
			withContext(Dispatchers.IO) {
				println("$$$$ Sending error response for request id ${rawRequest.requestId}, message: $message")
				sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				println("$$$$ Finished sending error response with id ${rawRequest.requestId}")
			}
			return
		}
		finally {
			println("$$$ processPacketAsRequest.finally: ${request.requestId}")
			_pendingServerMethodByRequestId.remove(request.requestId)
		}
	}

	private suspend fun processResponse(response: LapisRpcPacket.Response) {
		println("$$$$ process response for request id ${response.requestId}")

		val gate = _responseProcessingGates.getOrPut(response.requestId) {
			CompletableDeferred()
		}

		try {
			val method = _pendingClientMethodByRequestId[response.requestId] ?: error("No pending method found for response id: ${response.requestId}")

			val adapter = getMethodAdapter(method)

			println("$$$$ Found pending method for response with id ${response.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

			val outputType = adapter.getOutputType(method)

			val serializer = serializationStrategy.serializerForClass(outputType) ?: error("No serializer found for return type: ${method.returnType.kotlin}")

			val deserializedResult = serializer.deserialize(ByteArrayInputStream(response.rawData))

			val lapisResponse = LapisResponse(
				requestId = response.requestId,
				data = deserializedResult,
			)

			adapter.onResult(
				requestId = response.requestId,
				result = deserializedResult,
			)

			interceptor.interceptIncomingResponse(deviceAddress = deviceAddress, response = lapisResponse)

		}
		finally {
			gate.complete(Unit)
		}
	}

	private fun processErrorResponse(errorResponse: LapisRpcPacket.ErrorResponse) {
		println("$$$$ process error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val method = _pendingClientMethodByRequestId.remove(errorResponse.requestId) ?: error("No pending method found for error response id: ${errorResponse.requestId}")
		println("$$$$ Found pending method for error response with id ${errorResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val adapter = getMethodAdapter(method)

		adapter.onErrorMessage(
			requestId = errorResponse.requestId,
			throwable = LapisRemoteException(message = errorResponse.message),
		)
	}

	private fun processCancellation(cancellation: LapisRpcPacket.Cancellation) {
		println("$$$$ process cancellation for request id ${cancellation.requestId}")

		val method = _pendingServerMethodByRequestId.remove(cancellation.requestId) ?: error("No pending method found for cancellation id: ${cancellation.requestId}")

		println("$$$$ process cancellation for request id ${cancellation.requestId}: $method")

		val adapter = getMethodAdapter(method)

		adapter.onCancel(requestId = cancellation.requestId)
	}

	private suspend fun processMethodExecutionEnd(completion: LapisRpcPacket.Completion) {
		println("$$$$ process method execution end for request id ${completion.requestId}")

		val gate = _responseProcessingGates.getOrPut(completion.requestId) {
			CompletableDeferred()
		}

		gate.await()

		if (_responseProcessingGates.remove(completion.requestId) == null) {
			println("$$$$ [Warning] The method execution end for request id ${completion.requestId} was already processed")
			return
		}

		val method = _pendingClientMethodByRequestId.remove(completion.requestId) ?: error("No pending method found for method execution end id: ${completion.requestId}")

		val adapter = getMethodAdapter(method)

		adapter.onEnd(requestId = completion.requestId)
	}

	private fun processFlowParameterCollection(flowCollection: LapisRpcPacket.FlowParameter.Collection) {
		// TODO: Maybe we should add the service name and the method name to provide more informative error messages
		val flow = _pendingFlowParameterById.remove(flowCollection.flowId) ?: error("No pending flow found for argument flow collection for parameter '${flowCollection.parameterName}' with id: ${flowCollection.flowId}")
		_pendingFlowParameterJobById[flowCollection.flowId] = _scope.launch {
			try {
				flow.collect {
					sendFlowParameterEmission(
						requestId = flowCollection.requestId,
						flowId = flowCollection.flowId,
						parameterName = flowCollection.parameterName,
						value = it,
					)
				}
				println("$$$$ sendFlowParameterCompletion for flow ${flowCollection.flowId}")
				sendPacket(
					LapisRpcPacket.FlowParameter.Completion(
						requestId = flowCollection.requestId,
						flowId = flowCollection.flowId,
						parameterName = flowCollection.parameterName,
					)
				)
			}
			catch (_: RemoteCancellationException) {
				// no-op
			}
			catch (_: CancellationException) {
				println("$$$ Flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId} was cancelled locally")
				println("$$$$ sendFlowParameterCancellation for flow ${flowCollection.flowId}")
				sendPacket(
					LapisRpcPacket.FlowParameter.Cancellation(
						requestId = flowCollection.requestId,
						flowId = flowCollection.flowId,
						parameterName = flowCollection.parameterName,
					)
				)
			}
			catch (e: LocalException) {
				throw e.cause!!
			}
			catch (e: Throwable) {
				println("$$$ Error collecting flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId}: ${e.message}")
				val message = e.message ?: "Unknown error"
				println("$$$$ sendFlowParameterError for flow ${flowCollection.flowId}, message: $message")
				sendPacket(
					LapisRpcPacket.FlowParameter.Error(
						requestId = flowCollection.requestId,
						flowId = flowCollection.flowId,
						parameterName = flowCollection.parameterName,
						message = message,
					)
				)
			}
			finally {
				_pendingFlowParameterJobById.remove(flowCollection.flowId)
			}
		}
	}

	private fun processFlowParameterEmission(emission: LapisRpcPacket.FlowParameter.Emission) {
		println("$$$$ process argument flow emission for flow id ${emission.flowId}")

		val channel = _pendingFlowParameterChannelsById[emission.flowId] ?: error("No pending flow found for emission with id: ${emission.flowId}")

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
		val deserializedValue = serializer.deserialize(ByteArrayInputStream(emission.rawData))

		channel.trySend(deserializedValue)
	}

	private fun processFlowParameterCancellation(cancellation: LapisRpcPacket.FlowParameter.Cancellation) {
		println("$$$ process argument flow cancellation for flow id ${cancellation.flowId}")

		_pendingFlowParameterJobById.remove(cancellation.flowId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
		_pendingFlowParameterById.remove(cancellation.flowId)
	}

	private fun processFlowParameterCompletion(completion: LapisRpcPacket.FlowParameter.Completion) {
		println("$$$ process argument flow completion for flow id ${completion.flowId}")

		_pendingFlowParameterChannelsById.remove(completion.flowId)?.close()
	}

	private fun processFlowParameterError(error: LapisRpcPacket.FlowParameter.Error) {
		println("$$$ process argument flow error for flow id ${error.flowId}, message: ${error.message}")

		_pendingFlowParameterChannelsById.remove(error.flowId)?.close(LapisRemoteException(error.message))
	}

	private fun sendFlowParameterEmission(
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		println("$$$$ sendArgumentEmission for flow $flowId, value: $value")

		@Suppress("UNCHECKED_CAST")
		val serializer = serializationStrategy.serializerForClass(if (value == null) Nothing::class else value::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, value)
		val serializedValue = byteArrayOutputStream.toByteArray()

		val emission = LapisRpcPacket.FlowParameter.Emission(
			requestId = requestId,
			flowId = flowId,
			parameterName = parameterName,
			rawData = serializedValue,
		)

		_scope.launch {
			sendPacket(emission)
		}
	}

	private suspend fun sendPacket(
		packet: LapisRpcPacket,
		methodMetadataAnnotations: List<Annotation> = emptyList(),
	) {
		val byteArrayOutputStream = ByteArrayOutputStream()
		LapisPacketSerializer.serialize(byteArrayOutputStream, packet)

		packetProcessor.sendPacketData(
			type = packet.toType().byteValue,
			payload = byteArrayOutputStream.toByteArray(),
			methodMetadataAnnotations = methodMetadataAnnotations,
		)
	}

	private fun LapisRpcPacket.toType(): CompleteBluetoothPacket.Type = when (this) {
		is LapisRpcPacket.Request -> CompleteBluetoothPacket.Type.Request
		is LapisRpcPacket.Response -> CompleteBluetoothPacket.Type.Response
		is LapisRpcPacket.ErrorResponse -> CompleteBluetoothPacket.Type.ErrorResponse
		is LapisRpcPacket.Cancellation -> CompleteBluetoothPacket.Type.Cancellation
		is LapisRpcPacket.Completion -> CompleteBluetoothPacket.Type.Completion
		is LapisRpcPacket.FlowParameter.Collection -> CompleteBluetoothPacket.Type.FlowParameterCollection
		is LapisRpcPacket.FlowParameter.Emission -> CompleteBluetoothPacket.Type.FlowParameterEmission
		is LapisRpcPacket.FlowParameter.Cancellation -> CompleteBluetoothPacket.Type.FlowParameterCancellation
		is LapisRpcPacket.FlowParameter.Completion -> CompleteBluetoothPacket.Type.FlowParameterCompletion
		is LapisRpcPacket.FlowParameter.Error -> CompleteBluetoothPacket.Type.FlowParameterError
	}

	private fun getMethodAdapter(method: Method): LapisMethodAdapter {
		return _returnTypeAdapters.firstOrNull { it.shouldIntercept(method) } ?: error("No adapter found for method: $method")
	}

	private fun internalDispose() {
		if (_isDisposed) {
			return
		}
		_isDisposed = true

		println("$$$ internalDispose: ${hashCode()}")
		packetProcessor.dispose()

		_scope.cancel()

		_returnTypeAdapters.forEach { it.onUnregister() }
		_returnTypeAdapters.clear()
		_pendingClientMethodByRequestId.clear()
		_pendingServerMethodByRequestId.clear()
		_pendingFlowParameterChannelsById.clear()
		_pendingFlowParameterById.clear()
		_pendingFlowParameterJobById.clear()
		_responseProcessingGates.clear()
	}

	private fun generateId() = _nextId.getAndIncrement()

	companion object {
		private val TAG = this::class.qualifiedName!!
	}
}
