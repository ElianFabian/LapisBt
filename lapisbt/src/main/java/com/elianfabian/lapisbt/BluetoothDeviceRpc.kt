package com.elianfabian.lapisbt

import android.util.Log
import com.elianfabian.lapisbt.LapisBtIRpcImpl.Companion.BLUETOOTH_PACKET_LENGTH
import com.elianfabian.lapisbt.LapisBtIRpcImpl.Companion.Tag
import com.elianfabian.lapisbt.annotation.LapisBluetoothApi
import com.elianfabian.lapisbt.annotation.LapisBluetoothMethodCall
import com.elianfabian.lapisbt.annotation.LapisBluetoothParam
import com.elianfabian.lapisbt.model.BluetoothPacket
import com.elianfabian.lapisbt.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt.model.LapisBluetoothRequest
import com.elianfabian.lapisbt.util.asEnumeration
import com.elianfabian.lapisbt.util.readNBytesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.SequenceInputStream
import java.lang.reflect.Method
import java.util.ArrayList
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
	private val _packetFragmentsById = mutableMapOf<Int, MutableList<BluetoothPacket>>()
	private val _packetChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _completeBluetoothPacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)
	private val _pendingContinuationsByRequestId = mutableMapOf<UUID, Continuation<Any?>>()


	init {
		launchRawDataProcessing()
		launchPacketProcessing()
		launchCompletePacketProcessing()
	}


	fun functionCall(
		proxy: Any,
		apiInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	): Any? {
		val parameterTypes = method.parameterTypes
		val isSuspend = parameterTypes.isNotEmpty() && Continuation::class.java.isAssignableFrom(parameterTypes.last())

		if (isSuspend) {
			@Suppress("UNCHECKED_CAST")
			val continuation = args!!.last() as Continuation<Any?>
			return try {
				val apiAnnotation = apiInterface.getAnnotation(LapisBluetoothApi::class.java) ?: error("API interface ${apiInterface.name} is missing LapisBluetoothApi annotation")
				val apiName = apiAnnotation.name

				val methodAnnotation = method.getAnnotation(LapisBluetoothMethodCall::class.java) ?: error("Method ${method.name} is missing LapisBluetoothMethodCall annotation")
				val methodName = methodAnnotation.name

				val valueArgs = args.dropLast(1)
				val parametersNames = method.parameterAnnotations.map { annotations ->
					val paramAnnotation = annotations.filterIsInstance<LapisBluetoothParam>().firstOrNull() ?: error("All parameters of method ${method.name} must have LapisBluetoothParam annotation")
					paramAnnotation.name
				}

				val arguments = parametersNames.zip(valueArgs).toMap()

				val request = LapisBluetoothRequest(
					uuid = UUID.randomUUID(),
					apiName = apiName,
					methodName = methodName,
					arguments = arguments,
				)

				_pendingContinuationsByRequestId[request.uuid] = continuation

				_scope.launch {
					lapisBt.sendData(deviceAddress) { stream ->
						val dataStream = DataOutputStream(stream)

						dataStream.writeLong(request.uuid.mostSignificantBits)
						dataStream.writeLong(request.uuid.leastSignificantBits)
						dataStream.writeUTF(request.apiName)
						dataStream.writeUTF(request.methodName)
						dataStream.writeInt(request.arguments.size)

						// We'll have to create a more generic solution later
						for ((key, value) in request.arguments) {
							dataStream.writeUTF(key)
							when (value) {
								null -> {
									dataStream.writeByte(0)
								}
								is Int -> {
									dataStream.writeByte(1)
									dataStream.writeInt(value)
								}
								is String -> {
									dataStream.writeByte(2)
									dataStream.writeUTF(value)
								}
								else -> {
									error("Unsupported argument type: ${value::class.java.name}")
								}
							}
						}
					}
				}
				COROUTINE_SUSPENDED
			}
			catch (t: Throwable) {
				Dispatchers.Default.dispatch(continuation.context) {
					continuation.intercepted().resumeWithException(t)
				}
				COROUTINE_SUSPENDED
			}
		}
		else {
			// Later we'll support non-suspended functions too for Flow support
			error("Non-suspended functions aren't supported")
		}
	}


	private fun readFirstFragment(stream: DataInputStream, id: Int): BluetoothPacket.FirstFragment {
		val uuidMostSignificantBits = stream.readLong()
		val uuidLeastSignificantBits = stream.readLong()

		val type = UUID(uuidMostSignificantBits, uuidLeastSignificantBits)
		val length = stream.readInt()
		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 3 - Long.SIZE_BYTES * 2)

		return BluetoothPacket.FirstFragment(
			id = id,
			index = 0,
			type = type,
			length = length,
			payload = payload,
		)
	}

	private fun readFragment(stream: DataInputStream, id: Int, index: Int): BluetoothPacket.Fragment {
		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - Int.SIZE_BYTES * 2)

		return BluetoothPacket.Fragment(
			id = id,
			index = index,
			payload = payload,
		)
	}

	private fun createFragments(data: ByteArray) {

	}

	private fun launchRawDataProcessing() {
		_scope.launch {
			lapisBt.receiveData(deviceAddress) { stream ->
				while (true) {
					val bytes = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH)

					val dataStream = DataInputStream(ByteArrayInputStream(bytes))

					val id = dataStream.readInt()
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
							Log.wtf(Tag, "index was $index, index should never be negative")
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
						val packets = _packetFragmentsById.getOrPut(packet.id) {
							ArrayList(packet.length)
						}
						packets.add(packet)
					}
					is BluetoothPacket.Fragment -> {
						val packets = _packetFragmentsById[packet.id]!!
						packets.add(packet)

						// Actually the first packet should always be in the first position, we'll test and see
						val firstPacket = packets.firstOrNull { it.index == 0 } as? BluetoothPacket.FirstFragment ?: return@launch

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
										.sortedBy { it.index }
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

	private fun launchCompletePacketProcessing() {
		_scope.launch {
			for (completePacket in _completeBluetoothPacketChannel) {
				if (completePacket.type == LapisBluetoothRequest.ResponseType) {
					val dataStream = DataInputStream(completePacket.payloadStream)

					val uuidMostSignificantBits = dataStream.readLong()
					val uuidLeastSignificantBits = dataStream.readLong()
					val requestId = UUID(uuidMostSignificantBits, uuidLeastSignificantBits)

					val isSuccess = dataStream.readBoolean()
					val continuation = _pendingContinuationsByRequestId.remove(requestId) ?: continue

					if (isSuccess) {
						// For now we only support Int and String return types
						val returnTypeIndicator = dataStream.readByte().toInt()
						val returnValue: Any? = when (returnTypeIndicator) {
							0 -> null
							1 -> dataStream.readInt()
							2 -> dataStream.readUTF()
							else -> error("Unsupported return type indicator: $returnTypeIndicator")
						}

						Dispatchers.Default.dispatch(continuation.context) {
							continuation.resume(returnValue)
						}
					}
					else {
						val errorMessage = dataStream.readUTF()

						Dispatchers.Default.dispatch(continuation.context) {
							continuation.resumeWithException(RuntimeException(errorMessage))
						}
					}
				}
			}
		}
	}
}
