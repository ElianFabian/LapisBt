package com.elianfabian.lapisbt_rpc

import android.util.Log
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.DeviceNotConnectedException
import com.elianfabian.lapisbt_rpc.exception.RemoteException
import com.elianfabian.lapisbt_rpc.model.BluetoothPacket
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisCancellation
import com.elianfabian.lapisbt_rpc.model.LapisErrorResponse
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.serializer.CancellationSerializer
import com.elianfabian.lapisbt_rpc.serializer.ErrorResponseSerializer
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.asEnumeration
import com.elianfabian.lapisbt_rpc.util.getRawClass
import com.elianfabian.lapisbt_rpc.util.getSuspendReturnType
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import com.elianfabian.lapisbt_rpc.util.isSuspend
import com.elianfabian.lapisbt_rpc.util.logDebug
import com.elianfabian.lapisbt_rpc.util.padded
import com.elianfabian.lapisbt_rpc.util.readNBytesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.SequenceInputStream
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
// TODO: use the parameters name to sort the values so that neither the client nor the server has to worry about
//  the other of the parameters when defining a function
// TODO: add support for Result type
// TODO: add support for flows
// TODO: we could improve the performance by generating the complete packet while receiving the fragments
//  instead of waiting for all the packets to be received
internal class BluetoothDeviceRpc(
	private val deviceAddress: String,
	private val lapisBt: LapisBt,
	private val lapisRpc: LapisBtRpc,
) {
	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val _remotePacketsById = ConcurrentHashMap<UUID, MutableList<BluetoothPacket>>()
	private val _remotePacketChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _remoteCompletePacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _pendingContinuationsByRequestId = ConcurrentHashMap<UUID, Continuation<Any?>>()
	private val _pendingPacketToSendChannel = Channel<BluetoothPacket>(capacity = 1)
	private val _pendingMethodByRequestId = ConcurrentHashMap<UUID, Method>()
	private val _activeServerJobs = ConcurrentHashMap<UUID, Job>()


	init {
		_scope.launch {
			lapisBt.events.collect { event ->
				when (event) {
					is LapisBt.Event.OnDeviceDisconnected -> {
						if (event.disconnectedDevice.address != deviceAddress) {
							return@collect
						}

						_pendingMethodByRequestId.clear()
						_pendingPacketToSendChannel.close()

						_activeServerJobs.forEach { (_, job) ->
							job.cancel(CancellationException("Device '$deviceAddress' disconnected"))
						}
						_activeServerJobs.clear()

						_pendingContinuationsByRequestId.forEach { (_, continuation) ->
							continuation.resumeWithException(CancellationException("Device '$deviceAddress' disconnected"))
						}
						_pendingContinuationsByRequestId.clear()
					}
					else -> Unit
				}
			}
		}
		launchSendPacketProcessing()
		launchRawDataProcessing()
		launchPacketProcessing()
		launchCompletePacketProcessing()
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

		logDebug(TAG, "$$$$$ functionCall called with method: ${method.name}, args: ${args?.joinToString()}")
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

			val requestId = UUID.randomUUID()
			_pendingContinuationsByRequestId[requestId] = continuation
			_pendingMethodByRequestId[requestId] = method

			val rpcBlock = suspend {
				suspendCancellableCoroutine { cancellableContinuation ->
					_pendingContinuationsByRequestId[requestId] = cancellableContinuation
					_pendingMethodByRequestId[requestId] = method

					cancellableContinuation.invokeOnCancellation { cause ->
						logDebug(TAG, "$$$ invokeOnCompletion($requestId): $cause")
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
							val request = LapisRequest(
								requestId = requestId,
								apiName = apiName,
								methodName = methodName,
								arguments = argumentsByName.mapValues { (_, value) ->
									val byteArrayOutputStream = ByteArrayOutputStream()

									@Suppress("UNCHECKED_CAST")
									val serializer = DefaultSerializationStrategy.serializerForClass(value?.let { it::class } ?: Nothing::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${value?.let { it::class.qualifiedName } ?: "null"}")
									serializer.serialize(byteArrayOutputStream, value)
									byteArrayOutputStream.toByteArray()
								},
							)

							sendRequest(request)
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
		_scope.cancel()

		_pendingMethodByRequestId.clear()
		_pendingPacketToSendChannel.close()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("BluetoothDeviceRpc for '$deviceAddress' was disposed"))
		}
		_activeServerJobs.clear()

		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			continuation.resumeWithException(CancellationException("BluetoothDeviceRpc for '$deviceAddress' was disposed"))
		}
		_pendingContinuationsByRequestId.clear()
	}


	private suspend fun sendRequest(
		request: LapisRequest,
	) = withContext(Dispatchers.IO) {
		logDebug(TAG, "$$$$ Sending request with id ${request.requestId}, api: ${request.apiName}, method: ${request.methodName}, arguments: ${request.arguments.keys.joinToString()}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		RequestSerializer.serialize(payloadStream, request)

		createPacketFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.Type.Request.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { packet ->
			logDebug(TAG, "$$$$ Sending request with as packet with id ${packet.packetId}, fragment type: ${if (packet is BluetoothPacket.FirstFragment) "FirstFragment, length: ${packet.length}, type: ${packet.type}" else "fragment"}, payload size: ${packet.payload.size}")

			_pendingPacketToSendChannel.send(packet)
		}

		logDebug(TAG, "$$$$ Finished sending request with id ${request.requestId}")
	}

	private suspend fun sendResponse(
		response: LapisResponse,
	) = withContext(Dispatchers.IO) {
		logDebug(TAG, "$$$$ Sending response for request id ${response.requestId}, data size: ${response.data.size}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ResponseSerializer.serialize(payloadStream, response)

		createPacketFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.Type.Response.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { packet ->
			logDebug(TAG, "$$$$ Sending response as packet with id ${packet.packetId}, fragment type: ${if (packet is BluetoothPacket.FirstFragment) "FirstFragment, length: ${packet.length}" else "fragment"}, payload size: ${packet.payload.size}")

			_pendingPacketToSendChannel.send(packet)
		}

		logDebug(TAG, "$$$$ Finished sending response with id ${response.requestId}")
	}

	private suspend fun sendErrorResponse(
		errorResponse: LapisErrorResponse,
	) = withContext(Dispatchers.IO) {
		logDebug(TAG, "$$$$ Sending error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ErrorResponseSerializer.serialize(payloadStream, errorResponse)

		createPacketFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.Type.ErrorResponse.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { packet ->
			logDebug(TAG, "$$$$ Sending error response as packet with id ${packet.packetId}, fragment type: ${if (packet is BluetoothPacket.FirstFragment) "FirstFragment, length: ${packet.length}" else "fragment"}, payload size: ${packet.payload.size}")

			_pendingPacketToSendChannel.send(packet)
		}

		logDebug(TAG, "$$$$ Finished sending error response with id ${errorResponse.requestId}")
	}

	private suspend fun sendCancellation(
		cancellation: LapisCancellation,
	) = withContext(Dispatchers.IO) {
		logDebug(TAG, "$$$$ Sending cancellation for request id ${cancellation.requestId}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		CancellationSerializer.serialize(payloadStream, cancellation)

		createPacketFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.Type.Cancellation.byteValue,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { packet ->
			logDebug(TAG, "$$$$ Sending cancellation as packet with id ${packet.packetId}, fragment type: ${if (packet is BluetoothPacket.FirstFragment) "FirstFragment, length: ${packet.length}" else "fragment"}, payload size: ${packet.payload.size}")

			_pendingPacketToSendChannel.send(packet)
		}

		logDebug(TAG, "$$$$ Finished sending cancellation with id ${cancellation.requestId}")
	}

	private fun serializePacket(
		stream: OutputStream,
		packet: BluetoothPacket,
	) {
		val dataStream = DataOutputStream(stream)

		when (packet) {
			is BluetoothPacket.FirstFragment -> {
				dataStream.writeLong(packet.packetId.mostSignificantBits)
				dataStream.writeLong(packet.packetId.leastSignificantBits)
				dataStream.writeByte(packet.type.toInt())
				dataStream.writeInt(packet.length)
				dataStream.write(packet.payload)
			}
			is BluetoothPacket.Fragment -> {
				dataStream.writeLong(packet.packetId.mostSignificantBits)
				dataStream.writeLong(packet.packetId.leastSignificantBits)
				//dataStream.writeInt(packet.index)
				dataStream.write(packet.payload)
			}
		}

		// calling flush() doesn't seem to make any difference in the performance
		//dataStream.flush()
		logDebug(TAG, "$$$$ packet sent: $packet")
	}

	private fun createPacketFragments(
		packetId: UUID,
		type: Byte,
		payload: ByteArray,
	): Sequence<BluetoothPacket> = sequence {
		val uuidBytesSize = Long.SIZE_BYTES * 2
		//val indexBytesSize = Int.SIZE_BYTES * 1
		val typeBytesSize = Byte.SIZE_BYTES * 1
		val lengthBytesSize = Int.SIZE_BYTES * 1

		val firstFragmentPayloadSize = BLUETOOTH_PACKET_LENGTH - uuidBytesSize - typeBytesSize - lengthBytesSize
		val remainingPayload = payload.size - firstFragmentPayloadSize
		val fragmentPayloadSize = BLUETOOTH_PACKET_LENGTH - uuidBytesSize// - indexBytesSize
		val numberOfFragments = if (remainingPayload <= 0) {
			0
		}
		else (remainingPayload + fragmentPayloadSize - 1) / fragmentPayloadSize

		val firstFragment = BluetoothPacket.FirstFragment(
			packetId = packetId,
			type = type,
			length = numberOfFragments,
			payload = payload.sliceArray(0 until minOf(payload.size, firstFragmentPayloadSize)).padded(firstFragmentPayloadSize).also { logDebug(TAG, "$$$ payload size: ${it.size}, target: $firstFragmentPayloadSize, full size: ${payload.size}") },
		)
		yield(firstFragment)

		for (index in 0 until numberOfFragments) {
			val start = firstFragmentPayloadSize + index * fragmentPayloadSize
			val end = minOf(start + fragmentPayloadSize, payload.size)
			val fragment = BluetoothPacket.Fragment(
				packetId = packetId,
				payload = payload.sliceArray(start until end).padded(fragmentPayloadSize).also { logDebug(TAG, "$$$ payload size: ${it.size}, target: $fragmentPayloadSize") },
			)
			yield(fragment)
		}
	}

	private fun deserializeFirstFragment(stream: DataInputStream, id: UUID): BluetoothPacket.FirstFragment {
		val uuidBytesSize = Long.SIZE_BYTES * 2
		val typeBytesSize = Byte.SIZE_BYTES * 1
		val lengthBytesSize = Int.SIZE_BYTES * 1

		val type = stream.readByte()
		val length = stream.readInt()
		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - uuidBytesSize - typeBytesSize - lengthBytesSize)

		return BluetoothPacket.FirstFragment(
			packetId = id,
			type = type,
			length = length,
			payload = payload,
		)
	}

	private fun deserializeFragment(stream: DataInputStream, id: UUID): BluetoothPacket.Fragment {
		val uuidBytesSize = Long.SIZE_BYTES * 2
		//val indexBytesSize = Int.SIZE_BYTES * 1

		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - uuidBytesSize)

		return BluetoothPacket.Fragment(
			packetId = id,
			payload = payload,
		)
	}

	private fun launchRawDataProcessing() {
		_scope.launch {
			lapisBt.receiveData(deviceAddress) { stream ->
				logDebug(TAG, "$$$ Start receiving data")
				while (true) {
					val bytes = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH)

					logDebug(TAG, "$$$$ Received raw data with size ${bytes.size}")

					val dataStream = DataInputStream(ByteArrayInputStream(bytes))

					val mostSignificantBits = dataStream.readLong()
					val leastSignificantBits = dataStream.readLong()
					val id = UUID(mostSignificantBits, leastSignificantBits)

					val packets = _remotePacketsById.getOrPut(id) {
						mutableListOf()
					}

					val packet = if (packets.isEmpty()) {
						deserializeFirstFragment(
							stream = dataStream,
							id = id,
						)
					}
					else {
						deserializeFragment(
							stream = dataStream,
							id = id,
						)
					}

					packets.add(packet)

					_remotePacketChannel.send(packet)
				}
			}
		}
	}

	private fun launchPacketProcessing() {
		_scope.launch {
			val currentPacketCountById = mutableMapOf<UUID, Int>()
			for (packet in _remotePacketChannel) {
				logDebug(TAG, "$$$$ processing packet: $packet")
				when (packet) {
					is BluetoothPacket.FirstFragment -> {
						logDebug(TAG, "$$$$ Stored first fragment with id ${packet.packetId}, type: ${packet.type}, length: ${packet.length}, payload size: ${packet.payload.size}")
						if (packet.length == 0) {
							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(packet.type),
								payloadStream = ByteArrayInputStream(packet.payload),
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)
						}
						else {
							currentPacketCountById[packet.packetId] = 0
						}
					}
					is BluetoothPacket.Fragment -> {
						logDebug(TAG, "$$$$ Stored fragment with id ${packet.packetId}, payload size: ${packet.payload.size}")
						val packets = _remotePacketsById[packet.packetId]!!

						val firstPacket = packets.firstOrNull() as? BluetoothPacket.FirstFragment ?: throw IllegalStateException("There should be a FirstFragment packet when processing a Fragment packet")

						val previousPacketCount = currentPacketCountById[packet.packetId] ?: throw IllegalStateException("Current packet count should be tracked for packet id ${packet.packetId}")
						val currentPacketCount = previousPacketCount + 1
						currentPacketCountById[packet.packetId] = currentPacketCount

						val isFinalFragment = currentPacketCount == firstPacket.length
						if (isFinalFragment) {
							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(firstPacket.type),
								payloadStream = SequenceInputStream(
									packets.asSequence()
										.map { ByteArrayInputStream(it.payload) }
										.iterator()
										.asEnumeration()
								)
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)
							currentPacketCountById.remove(packet.packetId)

							logDebug(TAG, "$$$$ Assembled complete packet with id ${completePacket.packetId}, type: ${completePacket.type}, payload size: ${packets.sumOf { it.payload.size }}")
						}
						else if (currentPacketCount > firstPacket.length) {
							throw IllegalStateException("Received more fragments than expected for packet id ${packet.packetId}")
						}
					}
				}
			}
		}
	}

	private suspend fun processPacketAsRequest(completePacket: CompleteBluetoothPacket) {
		val request = RequestSerializer.deserialize(completePacket.payloadStream)
		logDebug(TAG, "$$$$ process deserialized request with id ${request.requestId}, api: ${request.apiName}, method: ${request.methodName}, arguments: ${request.arguments.keys.joinToString()}")

		val serverImplementation = lapisRpc.getBluetoothServerApiByName(
			deviceAddress = deviceAddress,
			apiName = request.apiName,
		)

		logDebug(TAG, "$$$$ Found server implementation for API ${request.apiName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val apiInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == request.apiName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = request.requestId,
				message = "No interface found for API: ${request.apiName}",
			)
		)

		val method = apiInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			logDebug(TAG, "$$$$ Checking method ${method.name} with annotation ${annotation?.name} against request method name ${request.methodName}")
			annotation?.name == request.methodName
		} ?: return sendErrorResponse(
			LapisErrorResponse(
				requestId = request.requestId,
				message = "No method found with name ${request.methodName} in API ${request.apiName}",
			)
		)

		// TODO: at the moment all functions are suspended, but later we'll support non-suspended functions too, so we'll have to check if the method is suspended or not and call it accordingly
		if (!method.isSuspend()) {
			sendErrorResponse(
				LapisErrorResponse(
					requestId = request.requestId,
					message = "The method '${request.methodName}' in API ${request.apiName} is not marked as suspend",
				)
			)
			error("Received request for non-suspended method ${method.name}, but only suspended methods are supported at the moment")
		}

		val parametersNames = method.parameterAnnotations.dropLast(1).map { annotations ->
			val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
			paramAnnotation.name
		}

		val missingParameters = mutableListOf<String>()
		val args = parametersNames.mapIndexedNotNull { index, name ->
			val valueBytes = request.arguments[name]
			if (valueBytes == null) {
				missingParameters.add(name)
				return@mapIndexedNotNull null
			}

			val valueType = method.parameterTypes[index]
			val serializer = DefaultSerializationStrategy.serializerForClass(valueType.kotlin)
			val valueStream = ByteArrayInputStream(valueBytes)

			serializer?.deserialize(valueStream)
		}.toTypedArray()

		if (missingParameters.isNotEmpty()) {
			val message = "Missing parameters from request ${request.requestId} for API server ${request.apiName}, and method ${request.methodName}: $missingParameters"

			sendErrorResponse(
				LapisErrorResponse(
					requestId = request.requestId,
					message = message,
				)
			)

			Log.w(TAG, message)
			return
		}

		request.arguments.keys.forEach { clientParameterName ->
			if (clientParameterName !in parametersNames) {
				Log.w(TAG, "Client parameter '$clientParameterName' not defined in server method '${method.name}' with API name '${request.apiName}'")
			}
		}

		val currentJob = currentCoroutineContext()[Job]
		if (currentJob != null) {
			_activeServerJobs[request.requestId] = currentJob
		}

		val result = try {
			method.invokeSuspend(serverImplementation, *args)
		}
		catch (e: CancellationException) {
			throw e
		}
		catch (e: Throwable) {
			sendErrorResponse(
				LapisErrorResponse(
					requestId = request.requestId,
					message = e.stackTraceToString(),
				)
			)
			return
		}
		finally {
			_activeServerJobs.remove(request.requestId)
		}


		logDebug(TAG, "$$$$ Method ${method.name}, with return type raw class: ${method.returnType.getRawClass()}, with generic return type raw class: ${method.genericReturnType.getRawClass()}, suspend type: ${method.getSuspendReturnType()}, with args: $args, returned result: $result")

		@Suppress("UNCHECKED_CAST")
		val serializer = DefaultSerializationStrategy.serializerForClass(if (result == null) Nothing::class else result::class) as? LapisSerializer<Any?> ?: error("No serializer registered for return type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, result)
		val serializedResult = byteArrayOutputStream.toByteArray()

		val response = LapisResponse(
			requestId = request.requestId,
			data = serializedResult,
		)

		sendResponse(response)
	}

	private fun processPacketAsResponse(completePacket: CompleteBluetoothPacket) {
		val response = ResponseSerializer.deserialize(completePacket.payloadStream)
		logDebug(TAG, "$$$$ process deserialized response for request id ${response.requestId}, data size: ${response.data.size}")

		val method = _pendingMethodByRequestId.remove(response.requestId) ?: error("No pending method found for response id: ${response.requestId}")
		logDebug(TAG, "$$$$ Found pending method for response with id ${response.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")
		val serializer = DefaultSerializationStrategy.serializerForClass(method.getSuspendReturnType().kotlin) ?: error("No serializer found for return type: ${method.returnType}")

		val deserializedResult = serializer.deserialize(ByteArrayInputStream(response.data))

		val continuation = _pendingContinuationsByRequestId.remove(response.requestId) ?: error("No pending continuation found for response id: ${response.requestId}")

		continuation.resume(deserializedResult)
	}

	private fun processPacketAsErrorResponse(completePacket: CompleteBluetoothPacket) {
		val errorResponse = ErrorResponseSerializer.deserialize(completePacket.payloadStream)
		logDebug(TAG, "$$$$ process deserialized error response for request id ${errorResponse.requestId}, message: ${errorResponse.message}")

		val method = _pendingMethodByRequestId.remove(errorResponse.requestId) ?: error("No pending method found for error response id: ${errorResponse.requestId}")
		logDebug(TAG, "$$$$ Found pending method for error response with id ${errorResponse.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")

		val continuation = _pendingContinuationsByRequestId.remove(errorResponse.requestId) ?: error("No pending continuation found for error response id: ${errorResponse.requestId}")

		val exception = RemoteException(errorResponse.message)

		continuation.resumeWithException(exception)
	}

	private fun processPacketAsCancellation(completePacket: CompleteBluetoothPacket) {
		val cancellation = CancellationSerializer.deserialize(completePacket.payloadStream)

		logDebug(TAG, "$$$$ process deserialized cancellation for request id ${cancellation.requestId}")

		_activeServerJobs.remove(cancellation.requestId)?.cancel()
	}

	// 2026-04-07 15:28:07.446  2113-2233  InputDispatcher         system_server                        E  channel '5ed9c09 com.elianfabian.lapisbt.app/com.elianfabian.lapisbt.app.MainActivity (server)' ~ Channel is unrecoverably broken and will be disposed!
	// It is strange that the received log is printted before the assembled log, maybe it's a threading issue?
	// Received complete packet with id afbb807b-7b54-4faf-b262-576d7d63a535, type: Request
	// Assembled complete packet with id afbb807b-7b54-4faf-b262-576d7d63a535, type: Request, payload size: 83307
	private fun launchCompletePacketProcessing() {
		_scope.launch(Dispatchers.IO) {
			for (completePacket in _remoteCompletePacketChannel) {
				logDebug(TAG, "$$$$ Received complete packet with id ${completePacket.packetId}, type: ${completePacket.type}")
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

	private fun launchSendPacketProcessing() {
		_scope.launch {
			lapisBt.sendData(deviceAddress) { stream ->
				for (packetToSend in _pendingPacketToSendChannel) {
					serializePacket(
						stream = stream,
						packet = packetToSend,
					)
				}
			}
		}
	}


	companion object {
		const val BLUETOOTH_PACKET_LENGTH = 256

		private val TAG = BluetoothDeviceRpc::class.simpleName!!
	}
}
