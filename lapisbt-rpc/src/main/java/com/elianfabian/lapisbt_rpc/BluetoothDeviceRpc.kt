package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt_rpc.util.asEnumeration
import com.elianfabian.lapisbt_rpc.util.readNBytesCompat
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.model.BluetoothPacket
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.serializer.RequestSerializer
import com.elianfabian.lapisbt_rpc.serializer.ResponseSerializer
import com.elianfabian.lapisbt_rpc.util.invokeSuspend
import com.elianfabian.lapisbt_rpc.util.isSuspend
import com.elianfabian.lapisbt_rpc.util.padded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.ArrayList
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
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
			val parametersNames = method.parameterAnnotations.map { annotations ->
				val paramAnnotation = annotations.filterIsInstance<LapisParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have ${LapisParam::class.simpleName} annotation")
				paramAnnotation.name
			}

			val argumentsByName = parametersNames.zip(valueArgs).toMap()

			val uuid = UUID.randomUUID()
			_pendingContinuationsByRequestId[uuid] = continuation
			continuation.context[Job]?.invokeOnCompletion {
				_pendingContinuationsByRequestId.remove(uuid)
				_pendingMethodByRequestId.remove(uuid)
			}
			_pendingMethodByRequestId[uuid] = method


			// TODO: maybe we should serialize and deserialize using byte arrays instead of streams

			_scope.launch {
				lapisBt.sendData(deviceAddress) { stream ->
					sendRequest(
						stream = stream,
						uuid = uuid,
						apiName = apiName,
						methodName = methodName,
						arguments = argumentsByName,
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


	private suspend fun sendRequest(
		stream: OutputStream,
		uuid: UUID,
		apiName: String,
		methodName: String,
		arguments: Map<String, Any?>,
	) = withContext(Dispatchers.IO) {
		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)
		payloadStream.writeUTF(apiName)
		payloadStream.writeUTF(methodName)
		payloadStream.writeInt(arguments.size)

		for ((key, value) in arguments) {
			// TODO: maybe we could exclude the key from serialization since we only take care of the order of arguments and not their names on the receiving end,
			//  it may be cool if this could be a feature we could enable or disable, or even allow the user to have total control over the serialization format of the payload
			payloadStream.writeUTF(key)

			val valueClass = value?.let { it::class } ?: Nothing::class

			@Suppress("UNCHECKED_CAST")
			val serializer = DefaultSerializationStrategy.serializerForClass(valueClass) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${valueClass.qualifiedName}")
			serializer.serialize(payloadStream, value)
		}

		val dataStream = DataOutputStream(stream)

		createFragments(
			id = uuid,
			type = CompleteBluetoothPacket.TYPE_REQUEST,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { fragment ->
			when (fragment) {
				is BluetoothPacket.FirstFragment -> {
					dataStream.writeLong(fragment.id.mostSignificantBits)
					dataStream.writeLong(fragment.id.leastSignificantBits)
					dataStream.writeByte(fragment.type.toInt())
					dataStream.writeInt(fragment.length)
					dataStream.write(fragment.payload)
				}
				is BluetoothPacket.Fragment -> {
					dataStream.writeLong(fragment.id.mostSignificantBits)
					dataStream.writeLong(fragment.id.leastSignificantBits)
					dataStream.writeInt(fragment.index)
					dataStream.write(fragment.payload)
				}
			}

			// I'm not sure if flush is necessary here, we'll see during testing
			dataStream.flush()

			// Yield to allow other coroutines to run, this way we can send multiple requests concurrently
			// without being blocked by large payloads
			yield()
		}
	}

	private suspend fun sendResponse(
		stream: OutputStream,
		uuid: UUID,
		result: Any?,
	) = withContext(Dispatchers.IO) {
		val byteArrayOutputStream = ByteArrayOutputStream()
		val payloadStream = DataOutputStream(byteArrayOutputStream)

		@Suppress("UNCHECKED_CAST")
		val serializer = DefaultSerializationStrategy.serializerForClass(result?.let { it::class } ?: Nothing::class) as? LapisSerializer<Any?> ?: error("No serializer registered for type: ${result?.let { it::class.qualifiedName } ?: "null"}")
		serializer.serialize(payloadStream, result)

		val dataStream = DataOutputStream(stream)

		createFragments(
			id = uuid,
			type = CompleteBluetoothPacket.TYPE_RESPONSE,
			payload = byteArrayOutputStream.toByteArray(),
		).forEach { fragment ->
			when (fragment) {
				is BluetoothPacket.FirstFragment -> {
					dataStream.writeLong(fragment.id.mostSignificantBits)
					dataStream.writeLong(fragment.id.leastSignificantBits)
					dataStream.writeByte(fragment.type.toInt())
					dataStream.writeInt(fragment.length)
					dataStream.write(fragment.payload)
				}
				is BluetoothPacket.Fragment -> {
					dataStream.writeLong(fragment.id.mostSignificantBits)
					dataStream.writeLong(fragment.id.leastSignificantBits)
					dataStream.writeInt(fragment.index)
					dataStream.write(fragment.payload)
				}
			}

			// I'm not sure if flush is necessary here, we'll see during testing
			dataStream.flush()

			// Yield to allow other coroutines to run, this way we can send multiple responses concurrently
			// without being blocked by large payloads
			yield()
		}
	}

	private fun createFragments(
		id: UUID,
		type: Byte,
		payload: ByteArray,
	): Sequence<BluetoothPacket> = sequence {

		val uuidBytesSize = Long.SIZE_BYTES * 2
		val indexBytesSize = Int.SIZE_BYTES * 1
		val typeBytesSize = Byte.SIZE_BYTES * 1
		val lengthBytesSize = Int.SIZE_BYTES * 1

		val firstFragmentFixedSize = uuidBytesSize + indexBytesSize + typeBytesSize + lengthBytesSize
		val firstFragmentPayloadSize = LapisBtRpcImpl.BLUETOOTH_PACKET_LENGTH - firstFragmentFixedSize
		val remainingPayload = payload.size - firstFragmentPayloadSize
		val fragmentPayloadSize = LapisBtRpcImpl.BLUETOOTH_PACKET_LENGTH - Long.SIZE_BYTES * 2 - Int.SIZE_BYTES * 1
		val numberOfFragments = if (remainingPayload <= 0) {
			0
		}
		else (remainingPayload + fragmentPayloadSize - 1) / fragmentPayloadSize

		val firstFragment = BluetoothPacket.FirstFragment(
			id = id,
			type = type,
			length = numberOfFragments,
			payload = payload.sliceArray(0 until minOf(firstFragmentPayloadSize, payload.size)),
		)
		yield(firstFragment)

		for (index in 0 until numberOfFragments) {
			val start = firstFragmentPayloadSize + index * fragmentPayloadSize
			val end = minOf(start + fragmentPayloadSize, payload.size)
			val fragment = BluetoothPacket.Fragment(
				id = id,
				index = index,
				payload = payload.sliceArray(start until end).padded(fragmentPayloadSize),
			)
			yield(fragment)
		}
	}

	private fun readFirstFragment(stream: DataInputStream, id: UUID): BluetoothPacket.FirstFragment {
		val type = stream.readByte()
		val length = stream.readInt()
		val payload = stream.readNBytesCompat(LapisBtRpcImpl.BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 3 - Long.SIZE_BYTES * 2)

		return BluetoothPacket.FirstFragment(
			id = id,
			type = type,
			length = length,
			payload = payload,
		)
	}

	private fun readFragment(stream: DataInputStream, id: UUID, index: Int): BluetoothPacket.Fragment {
		val payload = stream.readNBytesCompat(LapisBtRpcImpl.BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 2)

		return BluetoothPacket.Fragment(
			id = id,
			index = index,
			payload = payload,
		)
	}

	private fun launchRawDataProcessing() {
		_scope.launch {
			lapisBt.receiveData(deviceAddress) { stream ->
				while (true) {
					val bytes = stream.readNBytesCompat(LapisBtRpcImpl.BLUETOOTH_PACKET_LENGTH)

					val dataStream = DataInputStream(ByteArrayInputStream(bytes))

					val mostSignificantBits = dataStream.readLong()
					val leastSignificantBits = dataStream.readLong()
					val id = UUID(mostSignificantBits, leastSignificantBits)
					val packet = when (val index = dataStream.readInt()) {
						0 -> {
							readFirstFragment(
								stream = dataStream,
								id = id,
							)
						}
						in 1..Int.MAX_VALUE -> {
							readFragment(
								stream = dataStream,
								id = id,
								index = index,
							)
						}
						else -> {
							//Log.wtf(Tag, "index was $index, index should never be negative")
							return@receiveData
						}
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
						val packets = _packetFragmentsById.put(packet.id, ArrayList(packet.length)) ?: return@launch
						packets.add(packet)
					}
					is BluetoothPacket.Fragment -> {
						val packets = _packetFragmentsById[packet.id]!!
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
								id = packet.id,
								type = firstPacket.type,
								payloadStream = SequenceInputStream(
									packets.asSequence()
										.map { ByteArrayInputStream(it.payload) }
										.iterator()
										.asEnumeration()
								)
							)
							_completeBluetoothPacketChannel.send(completePacket)
							_packetFragmentsById.remove(packet.id)
						}
					}
				}
			}
		}
	}

	fun isSuspend(method: Method): Boolean {
		val parameterTypes = method.parameterTypes
		if (parameterTypes.isEmpty()) return false

		// The last parameter of a compiled suspend function is always Continuation
		return Continuation::class.java.isAssignableFrom(parameterTypes.last())
	}

	private fun launchCompletePacketProcessing() {
		_scope.launch(Dispatchers.IO) {
			for (completePacket in _completeBluetoothPacketChannel) {
				when (completePacket.type) {
					CompleteBluetoothPacket.TYPE_REQUEST -> {
						val request = RequestSerializer.deserialize(completePacket.payloadStream)

						val serverImplementation = lapisRpc.getBluetoothApiServerByName(request.apiName) ?: error("No server implementation found for API: ${request.apiName}")

						val method = serverImplementation::class.java.methods.firstOrNull { method ->
							method.getAnnotation(LapisMethod::class.java)?.name == request.methodName
						} ?: error("No method found with name ${request.methodName} in API ${request.apiName}")

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

						lapisBt.sendData(deviceAddress) { stream ->
							sendResponse(
								stream = stream,
								uuid = request.uuid,
								result = result,
							)
						}
					}
					CompleteBluetoothPacket.TYPE_RESPONSE -> {
						val response = ResponseSerializer.deserialize(completePacket.payloadStream)

						val method = _pendingMethodByRequestId.remove(response.uuid) ?: error("No pending method found for response id: ${response.uuid}")
						val serializer = DefaultSerializationStrategy.serializerForClass(method.returnType.kotlin) ?: error("No serializer found for return type: ${method.returnType}")

						val deserializedResult = serializer.deserialize(ByteArrayInputStream(response.result))

						val continuation = _pendingContinuationsByRequestId.remove(response.uuid) ?: error("No pending continuation found for response id: ${response.uuid}")

						continuation.resume(deserializedResult)
					}
					else -> {
						//Log.wtf(Tag, "Unknown complete packet type: ${completePacket.type}")
					}
				}
			}
		}
	}
}
