package com.elianfabian.lapisbt_rpc.method_adapter

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.LapisLogger
import com.elianfabian.lapisbt_rpc.AutomaticEncryptionMarker
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.LapisEncryption
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
import com.elianfabian.lapisbt_rpc.exception.LapisHandshakeException
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
import com.elianfabian.lapisbt_rpc.util.LapisKeyExchange
import com.elianfabian.lapisbt_rpc.util.extractFirstGenericArgument
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import com.elianfabian.lapisbt_rpc.util.isSuspend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.security.KeyPair
import java.security.MessageDigest
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
	private val logger: LapisLogger,
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
	private val _flowEnd = Any()
	private val _nextId = AtomicInteger(0)

	private var _encryptionMarker: AutomaticEncryptionMarker? = null
	private var _handshakeReady: CompletableDeferred<Unit>? = null

	private var _handshakeKeyPair: KeyPair? = null

	private val _returnTypeAdapters = mutableSetOf(
		SuspendMethodAdapter(
			deviceAddress = deviceAddress,
			bluetoothDeviceRpc = this,
			logger = logger,
		),
		FlowMethodAdapter(
			deviceAddress = deviceAddress,
			bluetoothDeviceRpc = this,
			logger = logger,
		)
	)


	init {
		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Starting...")
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

						logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): instance disconnected and cleaned up (hash: ${hashCode()})")
						internalDispose()
					}
					else -> Unit
				}
			}
		}
		_scope.launch {
			while (isActive) {
				val success = lapisBt.sendData(deviceAddress) { stream ->
					packetProcessor.sendData(stream)
				}
				if (success) {
					logger.warning(TAG, "BluetoothDeviceRpc($deviceAddress): sendData loop completed unexpectedly")
					break
				}

				logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): sendData failed - device is not connected. Waiting for connection...")
				if (lapisBt.getRemoteDevice(deviceAddress).connectionState != BluetoothDevice.ConnectionState.Connected) {
					lapisBt.events.first { it is LapisBt.Event.OnDeviceConnected && it.device.address == deviceAddress }
				}
				else {
					// small delay to avoid a busy loop
					delay(100)
				}
			}
		}
		_scope.launch {
			while (isActive) {
				val success = lapisBt.receiveData(deviceAddress) { stream ->
					packetProcessor.receiveData(stream)
				}
				if (success) {
					logger.warning(TAG, "BluetoothDeviceRpc($deviceAddress): receiveData loop completed unexpectedly")
					break
				}

				logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): receiveData failed - device is not connected. Waiting for connection...")
				if (lapisBt.getRemoteDevice(deviceAddress).connectionState != BluetoothDevice.ConnectionState.Connected) {
					lapisBt.events.first { it is LapisBt.Event.OnDeviceConnected && it.device.address == deviceAddress }
				}
				else {
					// small delay to avoid a busy loop
					delay(100)
				}
			}
		}
		_scope.launch {
			try {
				for (completePacket in packetProcessor.remoteCompletePackets) {
					logger.verbose(TAG, "BluetoothDeviceRpc($deviceAddress): Received complete packet with id ${completePacket.packetId}, type: ${completePacket.type}")
					ensureActive()
					val packet = LapisPacketSerializer.deserialize(completePacket.type, completePacket.payloadStream)
					processPacket(packet)
				}
			}
			catch (e: Throwable) {
				if (e is CancellationException) throw e
				val lastRequestId = _pendingServerMethodByRequestId.keys.lastOrNull()
				logger.error(TAG, "Error in remoteCompletePackets loop($isActive): $lastRequestId", e)
				internalAllRequestsFailed(e)
			}
		}
	}

	private suspend fun internalAllRequestsFailed(throwable: Throwable) {
		_encryptionMarker = null
		// WE HAVE TO INFORM THE CLIENT THAT SOMETHING WAS WRONG SO THEY DON'T WAIT FOREVER (NO AI COMMENT)
		sendErrorMessage(0, throwable.message ?: "Unknown error")
		_handshakeReady?.completeExceptionally(throwable)
		_handshakeReady = null
		_returnTypeAdapters.forEach { it.onAllRequestsFailed(throwable) }
	}

	private suspend fun processPacket(packet: LapisRpcPacket) {
		logger.debug(TAG, "processPacket($deviceAddress): $packet")
		when (packet) {
			is LapisRpcPacket.Request -> _scope.launch { processRequest(packet) }
			is LapisRpcPacket.Response -> processResponse(packet)
			is LapisRpcPacket.ErrorResponse -> processErrorResponse(packet)
			is LapisRpcPacket.Cancellation -> processCancellation(packet)
			is LapisRpcPacket.Completion -> processMethodExecutionEnd(packet)
			is LapisRpcPacket.Handshake -> processHandshake(packet)
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
		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Proxy function call: ${method.name}(...)")

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

		val adapter = getMethodAdapter(method)

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

		// We have to remove the continuation parameter for suspend functions
		val methodParameterAnnotations = if (method.isSuspend()) {
			method.parameterAnnotations.dropLast(1)
		}
		else {
			method.parameterAnnotations.toList()
		}

		val parametersNames = methodParameterAnnotations.map { annotations ->
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

		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Preparing to send RPC request: $serviceName.$methodName(${argumentsByName.keys.joinToString()})")

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


		logger.verbose(TAG, "Sending request with id ${request.requestId}, service: ${request.serviceName}, method: ${request.methodName}, arguments: ${request.rawArguments.keys.joinToString()}")

		val interceptedRequest = LapisRequest(
			requestId = request.requestId,
			serviceName = request.serviceName,
			methodName = request.methodName,
			arguments = argumentsByName,
			metadata = metadata,
		)

		interceptor.interceptOutgoingRequest(deviceAddress = deviceAddress, request = interceptedRequest)

		sendPacket(request, methodMetadataAnnotations)

		logger.verbose(TAG, "Finished sending request with id ${request.requestId}")
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

		val interceptedResponse = LapisResponse(
			requestId = requestId,
			data = result,
		)
		interceptor.interceptOutgoingResponse(deviceAddress = deviceAddress, response = interceptedResponse)

		sendPacket(response)
	}

	suspend fun sendEnd(requestId: Int) {
		interceptor.interceptOutgoingMethodExecutionEnd(deviceAddress = deviceAddress, requestId = requestId)
		sendPacket(LapisRpcPacket.Completion(requestId))
	}

	suspend fun sendErrorMessage(
		requestId: Int,
		message: String,
	) {
		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Sending error response for request $requestId: $message")
		interceptor.interceptOutgoingErrorMessage(deviceAddress = deviceAddress, requestId = requestId, message = message)
		sendPacket(LapisRpcPacket.ErrorResponse(requestId, message))
	}

	suspend fun cancel(
		requestId: Int,
	) {
		logger.verbose(TAG, "cancel: $requestId")
		interceptor.interceptOutgoingCancellation(deviceAddress = deviceAddress, requestId = requestId)
		sendPacket(LapisRpcPacket.Cancellation(requestId))
	}

	fun dispose() {
		internalDispose()
	}

	fun setEncryption(encryption: LapisEncryption?) {
		if (encryption is AutomaticEncryptionMarker) {
			_encryptionMarker = encryption
			packetProcessor.encryptionRequired = true
			_handshakeReady = null // Reset handshake if encryption is set again
		}
		else {
			_encryptionMarker = null
			packetProcessor.encryption = encryption
			packetProcessor.encryptionRequired = encryption != null
			_handshakeReady = CompletableDeferred(Unit) // Already ready if manual encryption
		}
	}


	private suspend fun ensureEncryptionReady() {
		val ready = _handshakeReady
		if (ready == null || !ready.isCompleted) {
			if (ready == null) {
				val deferred = CompletableDeferred<Unit>()
				_handshakeReady = deferred

				_scope.launch {
					try {
						withTimeout(5000) {
							logger.debug(TAG, "ensureEncryptionReady.timeout1: ${hashCode()}")
							startHandshake()
							logger.debug(TAG, "ensureEncryptionReady.timeout2: ${hashCode()}")
							deferred.await()
							logger.debug(TAG, "ensureEncryptionReady.timeout3: ${hashCode()}")
						}
					}
					catch (e: Exception) {
						logger.error(TAG, "Handshake failed", e)
						val handshakeEx = if (e is LapisHandshakeException) e else LapisHandshakeException("Encryption handshake failed or timed out", e)
						deferred.completeExceptionally(handshakeEx)
						internalAllRequestsFailed(handshakeEx)
					}
					finally {
						// This ensures that if for some reason the above launch is the only thing keeping the test alive,
						// we don't hang if it's already failed.
					}
				}
				deferred.await()
				logger.debug(TAG, "ensureEncryptionReady.timeout5: ${hashCode()}")
			}
			else {
				logger.debug(TAG, "ensureEncryptionReady.timeout6: ${hashCode()}")
				ready.await()
				logger.debug(TAG, "ensureEncryptionReady.timeout7: ${hashCode()}")
			}
		}
	}

	private suspend fun startHandshake() {
		val keyPair = try {
			_handshakeKeyPair ?: LapisKeyExchange.generateKeyPair()
		}
		catch (e: Exception) {
			logger.error(TAG, "Failed to generate key pair", e)
			throw LapisHandshakeException("Failed to initiate handshake", e)
		}

		_handshakeKeyPair = keyPair

		val handshakePacket = LapisRpcPacket.Handshake(
			requestId = generateId(),
			publicKey = keyPair.public.encoded,
		)

		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Initiating handshake...")
		sendPacket(handshakePacket)
	}

	private fun processHandshake(packet: LapisRpcPacket.Handshake) {
		_scope.launch {
			try {
				val marker = _encryptionMarker
				if (marker == null) {
					logger.warning(TAG, "Received handshake packet but no automatic encryption is configured")
					return@launch
				}

				val myKeyPair = _handshakeKeyPair ?: LapisKeyExchange.generateKeyPair().also { _handshakeKeyPair = it }

				val ready = _handshakeReady
				if (ready == null || !ready.isCompleted) {
					// If we haven't initiated, send our public key too (Symmetric Handshake)
					if (ready == null) {
						logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Remote initiated handshake, responding with our public key")
						_handshakeReady = CompletableDeferred()
						startHandshake()
					}

					val sharedSecret = LapisKeyExchange.deriveSharedSecret(myKeyPair.private, packet.publicKey)

					val encryptionImpl = marker.factory?.invoke(sharedSecret) ?: run {
						val hashedSecret = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
						LapisEncryption.aesGcm(hashedSecret)
					}

					packetProcessor.encryption = encryptionImpl
					_handshakeReady?.complete(Unit)
					logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Handshake completed successfully")
				}
				else {
					logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Handshake already ready, ignoring redundant handshake packet")
				}
			}
			catch (e: Throwable) {
				if (e is CancellationException) throw e
				logger.error(TAG, "Error processing handshake", e)
				_handshakeReady?.completeExceptionally(e)
				internalAllRequestsFailed(e)
			}
		}
	}


	private suspend fun processRequest(rawRequest: LapisRpcPacket.Request) {
		val serverImplementation = lapisRpc.getBluetoothServerServiceByName(
			deviceAddress = deviceAddress,
			serviceName = rawRequest.serviceName,
		)

		logger.verbose(TAG, "Found server implementation for service ${rawRequest.serviceName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val serviceInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == rawRequest.serviceName
		} ?: run {
			val message = "No interface found for service: ${rawRequest.serviceName}"
			return withContext(Dispatchers.IO) {
				logger.verbose(TAG, "Sending error response for request id ${rawRequest.requestId}, message: $message")
				this@BluetoothDeviceRpc.sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				logger.verbose(TAG, "Finished sending error response with id ${rawRequest.requestId}")
			}
		}

		// TODO: we could create a map for fast method lookup
		val method = serviceInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			logger.verbose(TAG, "Checking method ${method.name} with annotation ${annotation?.name} against request method name ${rawRequest.methodName}")
			annotation?.name == rawRequest.methodName
		} ?: run {
			val message = "No method found with name ${rawRequest.methodName} in service ${rawRequest.serviceName}"
			return withContext(Dispatchers.IO) {
				logger.verbose(TAG, "Sending error response for request id ${rawRequest.requestId}, message: $message")
				this@BluetoothDeviceRpc.sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				logger.verbose(TAG, "Finished sending error response with id ${rawRequest.requestId}")
			}
		}

		_pendingServerMethodByRequestId[rawRequest.requestId] = method

		// We have to remove the last continuation parameter in suspend functions
		val methodParameterAnnotations = if (method.isSuspend()) {
			method.parameterAnnotations.dropLast(1)
		}
		else {
			method.parameterAnnotations.toList()
		}
		val parametersNames = methodParameterAnnotations.map { annotations ->
			val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
			paramAnnotation.name
		}

		val missingParameters = mutableListOf<String>()
		logger.verbose(TAG, "processRequest.parameterNames: $parametersNames | ${rawRequest.rawArguments.map { it.key to it.value.contentToString() }.toMap()}")
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

				logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Creating incoming Flow for parameter '$name' (ID: $flowId)")

				val flow = callbackFlow {
					_pendingFlowParameterChannelsById[flowId] = this

					logger.verbose(TAG, "sendFlowParameterCollection for flow $flowId")
					interceptor.interceptOutgoingFlowParameterCollection(
						deviceAddress = deviceAddress,
						requestId = rawRequest.requestId,
						flowId = flowId,
						parameterName = name
					)
					sendPacket(LapisRpcPacket.FlowParameter.Collection(requestId = rawRequest.requestId, flowId = flowId, parameterName = name))

					awaitClose {
						logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Local cancellation of incoming Flow for parameter '$name'")

						_pendingFlowParameterChannelsById.remove(flowId)
						_pendingFlowParameterJobById.remove(flowId)?.cancel()

						_scope.launch {
							logger.verbose(TAG, "sendFlowParameterCancellation for flow $flowId")
							interceptor.interceptOutgoingFlowParameterCancellation(
								deviceAddress = deviceAddress,
								requestId = rawRequest.requestId,
								flowId = flowId,
								parameterName = name
							)
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
				).transformWhile { value ->
					if (value === _flowEnd) {
						false
					}
					else {
						emit(value)
						true
					}
				}

				name to flow
			}
			else {
				val serializer = serializationStrategy.serializerForClass(valueType)
				logger.debug(TAG, "processRequest.valueType: $valueType, $serializer, name: $name")
				val valueStream = ByteArrayInputStream(valueBytes)

				name to serializer?.deserialize(valueStream)
			}
		}.toMap()

		if (missingParameters.isNotEmpty()) {
			val message = "Missing parameters from request ${rawRequest.requestId} for service ${rawRequest.serviceName}, and method ${rawRequest.methodName}: $missingParameters"

			withContext(Dispatchers.IO) {
				logger.verbose(TAG, "Sending error response for request id ${rawRequest.requestId}, message: $message")
				interceptor.interceptOutgoingErrorMessage(deviceAddress = deviceAddress, requestId = rawRequest.requestId, message = message)
				sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				logger.verbose(TAG, "Finished sending error response with id ${rawRequest.requestId}")
			}

			logger.warning(TAG, message)
			return
		}

		rawRequest.rawArguments.keys.forEach { clientParameterName ->
			if (clientParameterName !in parametersNames) {
				logger.warning(TAG, "Client parameter '$clientParameterName' not defined in server method '${method.name}' with service name '${rawRequest.serviceName}'")
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

		logger.verbose(TAG, "processRequest: $request")

		val adapter = getMethodAdapter(method)

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
			logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): RPC request ${request.requestId} was cancelled: ${e.message}")
			throw e
		}
		catch (e: Throwable) {
			logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Unexpected error processing RPC request ${request.requestId}: ${e.message}")
			val message = e.stackTraceToString()
			withContext(Dispatchers.IO) {
				logger.verbose(TAG, "Sending error response for request id ${rawRequest.requestId}, message: $message")
				interceptor.interceptOutgoingErrorMessage(deviceAddress = deviceAddress, requestId = rawRequest.requestId, message = message)
				sendPacket(LapisRpcPacket.ErrorResponse(requestId = rawRequest.requestId, message = message))
				logger.verbose(TAG, "Finished sending error response with id ${rawRequest.requestId}")
			}
			return
		}
		finally {
			logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Finished processing RPC request ${request.requestId}")
			_pendingServerMethodByRequestId.remove(request.requestId)
		}
	}

	private suspend fun processResponse(response: LapisRpcPacket.Response) {
		val gate = _responseProcessingGates.getOrPut(response.requestId) {
			CompletableDeferred()
		}

		try {
			val method = _pendingClientMethodByRequestId[response.requestId] ?: error("No pending method found for response id: ${response.requestId}")

			val adapter = getMethodAdapter(method)

			logger.verbose(TAG, "Found pending method for response with id ${response.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

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

	private suspend fun processErrorResponse(errorResponse: LapisRpcPacket.ErrorResponse) {
		val method = _pendingClientMethodByRequestId.remove(errorResponse.requestId) ?: error("No pending method found for error response id: ${errorResponse.requestId}")
		logger.verbose(TAG, "Found pending method for error response with id ${errorResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val adapter = getMethodAdapter(method)

		interceptor.interceptIncomingErrorMessage(
			deviceAddress = deviceAddress,
			requestId = errorResponse.requestId,
			message = errorResponse.message,
		)

		adapter.onErrorMessage(
			requestId = errorResponse.requestId,
			throwable = LapisRemoteException(message = errorResponse.message),
		)
	}

	private suspend fun processCancellation(cancellation: LapisRpcPacket.Cancellation) {
		val method = _pendingServerMethodByRequestId.remove(cancellation.requestId) ?: return run {
			logger.info(TAG, "No pending method found for cancellation id: ${cancellation.requestId}")
		}

		logger.verbose(TAG, "process cancellation for request id ${cancellation.requestId}: $method")

		val adapter = getMethodAdapter(method)

		interceptor.interceptIncomingCancellation(deviceAddress = deviceAddress, requestId = cancellation.requestId)

		adapter.onCancel(requestId = cancellation.requestId)
	}

	private suspend fun processMethodExecutionEnd(completion: LapisRpcPacket.Completion) {
		val gate = _responseProcessingGates.getOrPut(completion.requestId) {
			CompletableDeferred()
		}

		gate.await()

		if (_responseProcessingGates.remove(completion.requestId) == null) {
			logger.verbose(TAG, "[Warning] The method execution end for request id ${completion.requestId} was already processed")
			return
		}

		val method = _pendingClientMethodByRequestId.remove(completion.requestId) ?: error("No pending method found for method execution end id: ${completion.requestId}")

		val adapter = getMethodAdapter(method)

		interceptor.interceptOutgoingMethodExecutionEnd(deviceAddress = deviceAddress, requestId = completion.requestId)

		adapter.onEnd(requestId = completion.requestId)
	}

	private suspend fun processFlowParameterCollection(flowCollection: LapisRpcPacket.FlowParameter.Collection) {
		interceptor.interceptIncomingFlowParameterCollection(
			deviceAddress = deviceAddress,
			requestId = flowCollection.requestId,
			flowId = flowCollection.flowId,
			parameterName = flowCollection.parameterName
		)

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
				logger.verbose(TAG, "sendFlowParameterCompletion for flow ${flowCollection.flowId}")
				interceptor.interceptOutgoingFlowParameterCompletion(
					deviceAddress = deviceAddress,
					requestId = flowCollection.requestId,
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName
				)
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
				logger.debug(TAG, "Flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId} was cancelled locally")
				logger.verbose(TAG, "sendFlowParameterCancellation for flow ${flowCollection.flowId}")
				interceptor.interceptOutgoingFlowParameterCancellation(
					deviceAddress = deviceAddress,
					requestId = flowCollection.requestId,
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName
				)
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
				logger.debug(TAG, "Error collecting flow for parameter '${flowCollection.parameterName}' with id ${flowCollection.flowId}: ${e.message}")
				val message = e.message ?: "Unknown error"
				logger.verbose(TAG, "sendFlowParameterError for flow ${flowCollection.flowId}, message: $message")
				interceptor.interceptOutgoingFlowParameterError(
					deviceAddress = deviceAddress,
					requestId = flowCollection.requestId,
					flowId = flowCollection.flowId,
					parameterName = flowCollection.parameterName,
					message = message
				)
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
				_pendingFlowParameterChannelsById.remove(flowCollection.flowId)
			}
		}
	}

	private fun processFlowParameterEmission(emission: LapisRpcPacket.FlowParameter.Emission) {
		val channel = _pendingFlowParameterChannelsById[emission.flowId] ?: error("No pending flow found for emission with id: ${emission.flowId}")

		val method = _pendingServerMethodByRequestId[emission.requestId] ?: error("No pending method found for argument flow emission with id: ${emission.flowId}")
		logger.verbose(TAG, "Found pending method for argument flow emission with id ${emission.flowId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		var parameterIndex = -1
		for (parameterAnnotations in method.parameterAnnotations) {
			parameterIndex++

			val paramAnnotation = parameterAnnotations.filterIsInstance<LapisParam>().firstOrNull { it.name == emission.parameterName }
			logger.debug(TAG, "parameter index: $parameterIndex, parameter name: ${paramAnnotation?.name}, emission parameter name: ${emission.parameterName}")
			if (paramAnnotation != null) {
				break
			}
		}

		logger.verbose(TAG, "parameter index for emission with id ${emission.flowId} is $parameterIndex")

		if (parameterIndex == -1) {
			error("No parameter found with name '${emission.parameterName}' in method '${method.name}'")
		}

		if (!Flow::class.java.isAssignableFrom(method.parameterTypes[parameterIndex])) {
			error("Parameter with name '${emission.parameterName}' in method '${method.name}' was expected to be a Flow, but got ${method.parameterTypes[parameterIndex]}")
		}

		val flowGenericType = method.genericParameterTypes[parameterIndex].extractFirstGenericArgument().kotlin

		val serializer = serializationStrategy.serializerForClass(flowGenericType) ?: error("No serializer found for argument flow emission for value type $flowGenericType with id: ${emission.flowId}")
		val deserializedValue = serializer.deserialize(ByteArrayInputStream(emission.rawData))

		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Received flow emission for ID ${emission.flowId}")

		_scope.launch {
			interceptor.interceptIncomingFlowParameterEmission(
				deviceAddress = deviceAddress,
				requestId = emission.requestId,
				flowId = emission.flowId,
				parameterName = emission.parameterName,
				value = deserializedValue
			)
		}

		channel.trySend(deserializedValue)
	}

	private fun processFlowParameterCancellation(cancellation: LapisRpcPacket.FlowParameter.Cancellation) {
		_scope.launch {
			interceptor.interceptIncomingFlowParameterCancellation(
				deviceAddress = deviceAddress,
				requestId = cancellation.requestId,
				flowId = cancellation.flowId,
				parameterName = cancellation.parameterName
			)
		}

		_pendingFlowParameterJobById.remove(cancellation.flowId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
		_pendingFlowParameterById.remove(cancellation.flowId)
	}

	private fun processFlowParameterCompletion(completion: LapisRpcPacket.FlowParameter.Completion) {
		_scope.launch {
			interceptor.interceptIncomingFlowParameterCompletion(
				deviceAddress = deviceAddress,
				requestId = completion.requestId,
				flowId = completion.flowId,
				parameterName = completion.parameterName
			)
		}

		val channel = _pendingFlowParameterChannelsById.remove(completion.flowId) ?: return
		channel.trySend(_flowEnd)
		channel.close()
	}

	private fun processFlowParameterError(error: LapisRpcPacket.FlowParameter.Error) {
		_scope.launch {
			interceptor.interceptIncomingFlowParameterError(
				deviceAddress = deviceAddress,
				requestId = error.requestId,
				flowId = error.flowId,
				parameterName = error.parameterName,
				message = error.message
			)
		}

		_pendingFlowParameterChannelsById.remove(error.flowId)?.close(LapisRemoteException(error.message))
	}

	private suspend fun sendFlowParameterEmission(
		requestId: Int,
		flowId: Int,
		parameterName: String,
		value: Any?,
	) {
		logger.verbose(TAG, "sendArgumentEmission for flow $flowId, value: $value")

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

		interceptor.interceptOutgoingFlowParameterEmission(
			deviceAddress = deviceAddress,
			requestId = requestId,
			flowId = flowId,
			parameterName = parameterName,
			value = value
		)

		sendPacket(emission)
	}

	private suspend fun sendPacket(
		packet: LapisRpcPacket,
		methodMetadataAnnotations: List<Annotation> = emptyList(),
	) {
		// I ADDED A CHECK FOR THE MARKER BECAUSE WE ONLY HAVE TO SEND A HANDSHAKE FOR AUTOMATIC ENCRYPTION (NO AI COMMENT)
		if (packet !is LapisRpcPacket.Handshake && _encryptionMarker != null) {
			ensureEncryptionReady()
		}

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
		is LapisRpcPacket.Handshake -> CompleteBluetoothPacket.Type.Handshake
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

		logger.debug(TAG, "BluetoothDeviceRpc($deviceAddress): Disposing instance (hash: ${hashCode()})...")
		packetProcessor.dispose()

		_scope.cancel()

		_returnTypeAdapters.forEach { it.dispose() }
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
		private val TAG = BluetoothDeviceRpc::class.simpleName!!
	}
}
