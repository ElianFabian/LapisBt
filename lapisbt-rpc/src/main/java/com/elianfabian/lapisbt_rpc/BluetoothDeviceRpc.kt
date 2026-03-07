package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.model.BluetoothPacket
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.asEnumeration
import com.elianfabian.lapisbt_rpc.util.getRawClass
import com.elianfabian.lapisbt_rpc.util.getSuspendReturnType
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import com.elianfabian.lapisbt_rpc.util.isSuspend
import com.elianfabian.lapisbt_rpc.util.padded
import com.elianfabian.lapisbt_rpc.util.readNBytesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.lang.reflect.Method
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BluetoothDeviceRpc(
	private val deviceAddress: String,
	private val lapisBt: LapisBt,
	private val lapisRpc: LapisBtRpc,
) {
	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val _packetFragmentsById = mutableMapOf<UUID, MutableList<BluetoothPacket>>()
	private val _packetChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _completeBluetoothPacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _pendingContinuationsByRequestId = mutableMapOf<UUID, Continuation<Any?>>()
	private val _pendingMethodByRequestId = mutableMapOf<UUID, Method>()


	init {
		launchRawDataProcessing()
		launchPacketProcessing()
		launchCompletePacketProcessing()
	}


	@Suppress("UNUSED_PARAMETER")
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

			val requestId = UUID.randomUUID()
			_pendingContinuationsByRequestId[requestId] = continuation
			continuation.context[Job]?.invokeOnCompletion {
				_pendingContinuationsByRequestId.remove(requestId)
				_pendingMethodByRequestId.remove(requestId)
				println("$$$$ Continuation for request with id $requestId was cancelled, removed pending continuation and method")
			}
			_pendingMethodByRequestId[requestId] = method


			// TODO: maybe we should serialize and deserialize using byte arrays instead of streams

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

			_scope.launch {
				lapisBt.sendData(deviceAddress) { stream ->
					sendRequest(
						stream = stream,
						request = request,
					)
				}
			}
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

		// TODO: we probably need to add more clean up logic here
	}


	private suspend fun sendRequest(
		stream: OutputStream,
		request: LapisRequest,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending request with id ${request.requestId}, api: ${request.apiName}, method: ${request.methodName}, arguments: ${request.arguments.keys.joinToString()}")

		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		RequestSerializer.serialize(payloadStream, request)

		val dataStream = DataOutputStream(stream)

		createFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.TYPE_REQUEST,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { fragment ->
			println("$$$$ Sending request with as packet with id ${fragment.packetId}, fragment type: ${if (fragment is BluetoothPacket.FirstFragment) "FirstFragment, length: ${fragment.length}" else (fragment as BluetoothPacket.Fragment).index}, payload size: ${fragment.payload.size}")

			serializeFragment(
				stream = dataStream,
				fragment = fragment,
			)

			// Yield to allow other coroutines to run, this way we can send multiple requests concurrently
			// without being blocked by large payloads
			yield()
		}

		println("$$$$ Finished sending request with id ${request.requestId}")
	}

	private suspend fun sendResponse(
		stream: OutputStream,
		response: LapisResponse,
	) = withContext(Dispatchers.IO) {
		println("$$$$ Sending response for request id ${response.requestId}, data size: ${response.data.size}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		ResponseSerializer.serialize(payloadStream, response)

		createFragments(
			packetId = UUID.randomUUID(),
			type = CompleteBluetoothPacket.TYPE_RESPONSE,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { fragment ->
			println("$$$$ Sending response as packet with id ${fragment.packetId}, fragment type: ${if (fragment is BluetoothPacket.FirstFragment) "FirstFragment, length: ${fragment.length}" else (fragment as BluetoothPacket.Fragment).index}, payload size: ${fragment.payload.size}")

			serializeFragment(
				stream = stream,
				fragment = fragment,
			)

			// Yield to allow other coroutines to run, this way we can send multiple responses concurrently
			// without being blocked by large payloads
			yield()
		}

		println("$$$$ Finished sending response with id ${response.requestId}")
	}

	private fun serializeFragment(
		stream: OutputStream,
		fragment: BluetoothPacket,
	) {
		val dataStream = DataOutputStream(stream)

		when (fragment) {
			is BluetoothPacket.FirstFragment -> {
				dataStream.writeLong(fragment.packetId.mostSignificantBits)
				dataStream.writeLong(fragment.packetId.leastSignificantBits)
				dataStream.writeByte(fragment.type.toInt())
				dataStream.writeInt(fragment.length)
				dataStream.write(fragment.payload)
			}
			is BluetoothPacket.Fragment -> {
				dataStream.writeLong(fragment.packetId.mostSignificantBits)
				dataStream.writeLong(fragment.packetId.leastSignificantBits)
				dataStream.writeInt(fragment.index)
				dataStream.write(fragment.payload)
			}
		}

		// I'm not sure if flush is necessary here, we'll see during testing
		dataStream.flush()
	}

	private fun createFragments(
		packetId: UUID,
		type: Byte,
		payload: ByteArray,
	): Sequence<BluetoothPacket> = sequence {
		val uuidBytesSize = Long.SIZE_BYTES * 2
		val indexBytesSize = Int.SIZE_BYTES * 1
		val typeBytesSize = Byte.SIZE_BYTES * 1
		val lengthBytesSize = Int.SIZE_BYTES * 1

		val firstFragmentPayloadSize = BLUETOOTH_PACKET_LENGTH - uuidBytesSize - typeBytesSize - lengthBytesSize
		val remainingPayload = payload.size - firstFragmentPayloadSize
		val fragmentPayloadSize = BLUETOOTH_PACKET_LENGTH - uuidBytesSize - indexBytesSize
		val numberOfFragments = if (remainingPayload <= 0) {
			0
		}
		else (remainingPayload + fragmentPayloadSize - 1) / fragmentPayloadSize

		val firstFragment = BluetoothPacket.FirstFragment(
			packetId = packetId,
			type = type,
			length = numberOfFragments,
			payload = payload.sliceArray(0 until minOf(payload.size, firstFragmentPayloadSize)).padded(firstFragmentPayloadSize).also { println("$$$ payload size: ${it.size}, target: $firstFragmentPayloadSize, full size: ${payload.size}") },
		)
		yield(firstFragment)

		for (index in 0 until numberOfFragments) {
			val start = firstFragmentPayloadSize + index * fragmentPayloadSize
			val end = minOf(start + fragmentPayloadSize, payload.size)
			val fragment = BluetoothPacket.Fragment(
				packetId = packetId,
				index = index,
				payload = payload.sliceArray(start until end).padded(fragmentPayloadSize).also { println("$$$ payload size: ${it.size}, target: $fragmentPayloadSize") },
			)
			yield(fragment)
		}
	}

	private fun readFirstFragment(stream: DataInputStream, id: UUID): BluetoothPacket.FirstFragment {
		val type = stream.readByte()
		val length = stream.readInt()
		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 3 - Long.SIZE_BYTES * 2)

		return BluetoothPacket.FirstFragment(
			packetId = id,
			type = type,
			length = length,
			payload = payload,
		)
	}

	private fun readFragment(stream: DataInputStream, id: UUID, index: Int): BluetoothPacket.Fragment {
		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 2)

		return BluetoothPacket.Fragment(
			packetId = id,
			index = index,
			payload = payload,
		)
	}

	private fun launchRawDataProcessing() {
		_scope.launch {
			lapisBt.receiveData(deviceAddress) { stream ->
				println("$$$ Start receiving daaa")
				while (true) {
					val bytes = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH)

					println("$$$$ Received raw data with size ${bytes.size}")

					val dataStream = DataInputStream(ByteArrayInputStream(bytes))

					val mostSignificantBits = dataStream.readLong()
					val leastSignificantBits = dataStream.readLong()
					val id = UUID(mostSignificantBits, leastSignificantBits)

					val isFirstFragment = id !in _packetFragmentsById

					val packet = if (isFirstFragment) {
						readFirstFragment(
							stream = dataStream,
							id = id,
						)
					}
					else {
						val index = dataStream.readInt()
						readFragment(
							stream = dataStream,
							id = id,
							index = index,
						)
					}

					_packetChannel.send(packet)
				}
			}
		}
	}

	private fun launchPacketProcessing() {
		_scope.launch {
			for (packet in _packetChannel) {
				when (packet) {
					is BluetoothPacket.FirstFragment -> {
						println("$$$$ Stored first fragment with id ${packet.packetId}, type: ${packet.type}, length: ${packet.length}, payload size: ${packet.payload.size}")
						if (packet.length == 0) {
							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(packet.type),
								payloadStream = ByteArrayInputStream(packet.payload),
							)
							_completeBluetoothPacketChannel.send(completePacket)
							_packetFragmentsById.remove(packet.packetId)
						}
						else if (packet.length > 0) {
							_packetFragmentsById[packet.packetId] = mutableListOf(packet)
						}
						else {
							//Log.wtf(Tag, "Invalid packet length: ${packet.length} for packet with id: ${packet.id}")
						}
					}
					is BluetoothPacket.Fragment -> {
						println("$$$$ Stored fragment with id ${packet.packetId}, index: ${packet.index}, payload size: ${packet.payload.size}")
						val packets = _packetFragmentsById[packet.packetId]!!
						packets.add(packet)

						// Actually the first packet should always be in the first position, we'll test and see
						val firstPacket = packets.firstOrNull() as? BluetoothPacket.FirstFragment ?: return@launch

						// Maybe this should be '>='?
						if (packet.index == firstPacket.length - 1) {
//							val completePacket = CompleteBluetoothPacket(
//								id = packet.id,
//								type = firstPacket.type,
//								payloadStream = packets
//									.sortedBy { it.index }
//									.flatMap { it.payload.toList() }
//									.toByteArray(),
//							)
							// I think this should be more efficient
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
							_completeBluetoothPacketChannel.send(completePacket)
							_packetFragmentsById.remove(packet.packetId)

							println("$$$$ Assembled complete packet with id ${completePacket.packetId}, type: ${completePacket.type}, payload size: ${packets.sumOf { it.payload.size }}")
						}
					}
				}
			}
		}
	}

	private suspend fun processPacketAsRequest(completePacket: CompleteBluetoothPacket) {
		val request = RequestSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized request with id ${request.requestId}, api: ${request.apiName}, method: ${request.methodName}, arguments: ${request.arguments.keys.joinToString()}")

		val serverImplementation = lapisRpc.getBluetoothServerApiByName(
			deviceAddress = deviceAddress,
			apiName = request.apiName,
		)

		println("$$$$ Found server implementation for API ${request.apiName}: ${serverImplementation::class.qualifiedName}, impl: $serverImplementation")

		val apiInterface = serverImplementation::class.java.interfaces.firstOrNull { inter ->
			inter.getAnnotation(LapisRpc::class.java)?.name == request.apiName
		} ?: error("No interface found for API: ${request.apiName} for server $serverImplementation")

		val method = apiInterface.methods.firstOrNull { method ->
			val annotation = method.getAnnotation(LapisMethod::class.java)
			println("$$$$ Checking method ${method.name} with annotation ${annotation?.name} against request method name ${request.methodName}")
			annotation?.name == request.methodName
		} ?: error("No method found with name ${request.methodName} in API ${request.apiName} for server $serverImplementation")

		var index = 0
		val deserializedArguments = request.arguments.mapValues { (key, valueBytes) ->
			val valueStream = ByteArrayInputStream(valueBytes)
			val valueType = method.parameterTypes.getOrNull(index) ?: error("Not enough parameter types in method ${method.name} for argument $key")
			val serializer = DefaultSerializationStrategy.serializerForClass(valueType.kotlin)

			serializer?.deserialize(valueStream).also {
				index++
			}
		}

		// TODO: at the moment all functions are suspended, but later we'll support non-suspended functions too, so we'll have to check if the method is suspended or not and call it accordingly
		if (!method.isSuspend()) {
			throw IllegalArgumentException("Received request for non-suspended method ${method.name}, but only suspended methods are supported at the moment")
		}


		val result = method.invokeSuspend(serverImplementation, *deserializedArguments.values.toTypedArray())

		println("$$$$ Method ${method.name}, with return type raw class: ${method.returnType.getRawClass()}, with generic return type raw class: ${method.genericReturnType.getRawClass()}, suspend type: ${method.getSuspendReturnType()}, with args: $deserializedArguments, returned result: $result")

		@Suppress("UNCHECKED_CAST")
		val serializer = DefaultSerializationStrategy.serializerForClass(if (result == null) Nothing::class else result::class) as? LapisSerializer<Any?> ?: error("No serializer registered for return type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		val byteArrayOutputStream = ByteArrayOutputStream()
		serializer.serialize(byteArrayOutputStream, result)
		val serializedResult = byteArrayOutputStream.toByteArray()

		val response = LapisResponse(
			requestId = request.requestId,
			data = serializedResult,
		)

		lapisBt.sendData(deviceAddress) { stream ->
			sendResponse(
				stream = stream,
				response = response,
			)
		}
	}

	private fun processPacketAsResponse(completePacket: CompleteBluetoothPacket) {
		val response = ResponseSerializer.deserialize(completePacket.payloadStream)
		println("$$$$ process deserialized response for request id ${response.requestId}, data size: ${response.data.size}")

		val method = _pendingMethodByRequestId.remove(response.requestId) ?: error("No pending method found for response id: ${response.requestId}")
		println("$$$$ Found pending method for response with id ${response.requestId}: ${method.name}, return type: ${method.returnType}, return type kotlin: ${method.returnType.kotlin}, generic return type: ${method.genericReturnType}")
		val serializer = DefaultSerializationStrategy.serializerForClass(method.getSuspendReturnType().kotlin) ?: error("No serializer found for return type: ${method.returnType}")

		val deserializedResult = serializer.deserialize(ByteArrayInputStream(response.data))

		val continuation = _pendingContinuationsByRequestId.remove(response.requestId) ?: error("No pending continuation found for response id: ${response.requestId}")

		continuation.resume(deserializedResult)
	}

	private fun launchCompletePacketProcessing() {
		_scope.launch(Dispatchers.IO) {
			for (completePacket in _completeBluetoothPacketChannel) {
				println("$$$$ Received complete packet with id ${completePacket.packetId}, type: ${completePacket.type}")
				when (completePacket.type) {
					CompleteBluetoothPacket.Type.Request -> {
						processPacketAsRequest(completePacket)
					}
					CompleteBluetoothPacket.Type.Response -> {
						processPacketAsResponse(completePacket)
					}
				}
			}
		}
	}


	companion object {
		const val BLUETOOTH_PACKET_LENGTH = 256
	}
}
