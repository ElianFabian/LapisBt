package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt_rpc.model.BluetoothPacket
import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.util.CompressionUtil
import com.elianfabian.lapisbt_rpc.util.padded
import com.elianfabian.lapisbt_rpc.util.readNBytesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public interface LapisPacketProcessor {

	public val remoteCompletePackets: Channel<CompleteBluetoothPacket>

	public suspend fun sendData(stream: OutputStream)

	public suspend fun receiveData(stream: InputStream)

	public suspend fun sendPacketData(
		type: Byte,
		payload: ByteArray,
		methodMetadataAnnotations: List<Annotation>,
	)

	public fun dispose()
}


// TODO: we may change the compression implementation by using DeflateOutputStream and DeflateInputStream
internal class DefaultLapisPacketProcessor : LapisPacketProcessor {

	private companion object {

		const val BLUETOOTH_PACKET_LENGTH = 256

		const val PACKET_ID_SIZE = Int.SIZE_BYTES
		const val INDEX_METADATA_SIZE = Int.SIZE_BYTES

		// FirstFragment: packetId (4) + type (1) + length (4) + compressed (1) + originalPayloadSize (4) + actualPayloadSize (4) = 18
		const val FIRST_FRAGMENT_METADATA_SIZE = PACKET_ID_SIZE + 1 + 4 + 1 + 4 + 4
		const val FIRST_FRAGMENT_PAYLOAD_CAPACITY = BLUETOOTH_PACKET_LENGTH - FIRST_FRAGMENT_METADATA_SIZE

		// Fragment: packetId (4) + index (4) = 8
		const val FRAGMENT_METADATA_SIZE = PACKET_ID_SIZE + INDEX_METADATA_SIZE
		const val FRAGMENT_PAYLOAD_CAPACITY = BLUETOOTH_PACKET_LENGTH - FRAGMENT_METADATA_SIZE

		val TAG = this::class.qualifiedName!!
	}


	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _remotePacketsById = ConcurrentHashMap<Int, MutableList<BluetoothPacket>>()

	private val _remotePacketChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)
	override val remoteCompletePackets get() = _remoteCompletePacketChannel

	private val _remoteCompletePacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)

	private val _pendingPacketToSendChannel = Channel<BluetoothPacket>(capacity = 1)

	private val _nextPacketId = AtomicInteger(0)


	init {
		launchPacketProcessing()
	}


	override suspend fun sendData(stream: OutputStream) = withContext(Dispatchers.IO) {
		for (packetToSend in _pendingPacketToSendChannel) {
			serializePacket(
				stream = stream,
				packet = packetToSend,
			)
		}
	}

	override suspend fun receiveData(stream: InputStream) = withContext(Dispatchers.IO) {
		println("$$$ Start receiving data")
		while (true) {
			val bytes = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH)

			val dataStream = DataInputStream(ByteArrayInputStream(bytes))

			val id = dataStream.readInt()

			println("$$$$ Received raw data with size ${bytes.size} = $id")
			val packets = _remotePacketsById.getOrPut(id) {
				mutableListOf()
			}

			val packet = deserializePacket(
				stream = dataStream,
				id = id,
				index = if (packets.isEmpty()) -1 else dataStream.readInt(),
			)

			packets.add(packet)

			_remotePacketChannel.send(packet)
		}
	}

	override suspend fun sendPacketData(
		type: Byte,
		payload: ByteArray,
		methodMetadataAnnotations: List<Annotation>,
	) {
		val packets = sequence {
			val compressed = CompressionUtil.shouldCompress(payload)
			val actualPayload = if (compressed) {
				CompressionUtil.compress(payload)!!
			}
			else payload

			println("$$$ original: ${payload.size}, actual: ${actualPayload.size}")

			val firstFragmentPayloadSize = minOf(actualPayload.size, FIRST_FRAGMENT_PAYLOAD_CAPACITY)
			val remainingPayloadSize = actualPayload.size - firstFragmentPayloadSize
			val numberOfFragments = if (remainingPayloadSize <= 0) {
				0
			}
			else (remainingPayloadSize + FRAGMENT_PAYLOAD_CAPACITY - 1) / FRAGMENT_PAYLOAD_CAPACITY

			val packetId = generateId()

			val firstFragment = BluetoothPacket.FirstFragment(
				packetId = packetId,
				type = type,
				length = numberOfFragments,
				compressed = compressed,
				originalPayloadSize = payload.size,
				actualPayloadSize = actualPayload.size,
				payload = actualPayload.sliceArray(0 until firstFragmentPayloadSize)
			)
			yield(firstFragment)

			for (index in 0 until numberOfFragments) {
				val start = firstFragmentPayloadSize + (index * FRAGMENT_PAYLOAD_CAPACITY)
				val end = minOf(start + FRAGMENT_PAYLOAD_CAPACITY, actualPayload.size)
				val fragment = BluetoothPacket.Fragment(
					packetId = packetId,
					index = index,
					payload = actualPayload.sliceArray(start until end).also { println("$$$ payload size: ${it.size}, target: $FRAGMENT_PAYLOAD_CAPACITY") },
				)
				yield(fragment)
			}
		}

		packets.forEach { packet ->
			println("$$$$ Queued packet to send: $packet")
			_pendingPacketToSendChannel.send(packet)
		}
	}

	override fun dispose() {
		_scope.cancel()
		_remotePacketsById.clear()
		_remotePacketChannel.close()
		_remoteCompletePacketChannel.close()
		_pendingPacketToSendChannel.close()
	}


	private fun serializePacket(
		stream: OutputStream,
		packet: BluetoothPacket,
	) {
		val dataStream = DataOutputStream(stream)

		val byteArrayOutputStream = ByteArrayOutputStream(BLUETOOTH_PACKET_LENGTH)
		val packetStream = DataOutputStream(byteArrayOutputStream)

		when (packet) {
			is BluetoothPacket.FirstFragment -> {
				packetStream.writeInt(packet.packetId)
				packetStream.writeByte(packet.type.toInt())
				packetStream.writeInt(packet.length)
				packetStream.writeBoolean(packet.compressed)
				packetStream.writeInt(packet.originalPayloadSize)
				packetStream.writeInt(packet.actualPayloadSize)
				packetStream.write(packet.payload)
			}
			is BluetoothPacket.Fragment -> {
				packetStream.writeInt(packet.packetId)
				packetStream.writeInt(packet.index)
				packetStream.write(packet.payload)
			}
		}

		val bytesToWrite = byteArrayOutputStream.toByteArray()
		if (bytesToWrite.size > BLUETOOTH_PACKET_LENGTH) {
			throw IllegalStateException("The serialized packet with id ${packet.packetId} is too large to fit in a single Bluetooth packet. Size: ${bytesToWrite.size}, limit: $BLUETOOTH_PACKET_LENGTH")
		}
		if (bytesToWrite.size < BLUETOOTH_PACKET_LENGTH) {
			val paddedPacket = bytesToWrite.padded(BLUETOOTH_PACKET_LENGTH)
			dataStream.write(paddedPacket)
		}
		else {
			dataStream.write(bytesToWrite)
		}

		println("$$$$ packet sent: $packet")
	}

	private fun deserializePacket(
		stream: InputStream,
		id: Int,
		index: Int,
	): BluetoothPacket {
		if (index == -1) {
			return deserializeFirstFragment(stream, id)
		}

		val payload = stream.readNBytesCompat(FRAGMENT_PAYLOAD_CAPACITY)

		return BluetoothPacket.Fragment(
			packetId = id,
			index = index,
			payload = payload,
		)
	}

	private fun deserializeFirstFragment(stream: InputStream, id: Int): BluetoothPacket.FirstFragment {
		val dataStream = DataInputStream(stream)

		val type = dataStream.readByte()
		val length = dataStream.readInt()
		val compressed = dataStream.readBoolean()
		val originalPayloadSize = dataStream.readInt()
		val actualPayloadSize = dataStream.readInt()

		val payloadSize = minOf(actualPayloadSize, FIRST_FRAGMENT_PAYLOAD_CAPACITY)
		val payload = dataStream.readNBytesCompat(payloadSize)

		return BluetoothPacket.FirstFragment(
			packetId = id,
			type = type,
			length = length,
			compressed = compressed,
			originalPayloadSize = originalPayloadSize,
			actualPayloadSize = actualPayloadSize,
			payload = payload,
		)
	}

	private fun launchPacketProcessing() {
		_scope.launch {
			for (packet in _remotePacketChannel) {
				println("$$$$ processing packet: $packet")
				ensureActive()
				when (packet) {
					is BluetoothPacket.FirstFragment -> {
						println("$$$$ Stored first fragment with id ${packet.packetId}, type: ${packet.type}, length: ${packet.length}, original payload size: ${packet.originalPayloadSize}")
						if (packet.length == 0) {
							val actualPayload = packet.payload

							val decompressedPayload = if (packet.compressed) {
								CompressionUtil.decompress(actualPayload, packet.originalPayloadSize) ?: error("Failed to decompress payload")
							}
							else actualPayload

							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(packet.type),
								payloadStream = ByteArrayInputStream(decompressedPayload),
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)

							println("$$$$ Assembled complete packet with id ${completePacket.packetId}, type: ${completePacket.type}, payload size: ${decompressedPayload.size}")
						}
					}
					is BluetoothPacket.Fragment -> {
						println("$$$$ Stored fragment with id ${packet.packetId}, index: ${packet.index}, payload size: ${packet.payload.size}")
						val packets = _remotePacketsById[packet.packetId]!!

						val firstPacket = packets.firstOrNull() as? BluetoothPacket.FirstFragment ?: throw IllegalStateException("There should be a FirstFragment packet when processing a Fragment packet")

						if (packet.index == firstPacket.length - 1) {
							val fullPayloadBaos = ByteArrayOutputStream()
							fullPayloadBaos.write(firstPacket.payload)
							packets.filterIsInstance<BluetoothPacket.Fragment>()
								.sortedBy { it.index }
								.forEach { fullPayloadBaos.write(it.payload) }

							val fullPayload = fullPayloadBaos.toByteArray()
							val actualPayload = fullPayload.copyOfRange(0, firstPacket.actualPayloadSize)

							val decompressedPayload = if (firstPacket.compressed) {
								CompressionUtil.decompress(actualPayload, firstPacket.originalPayloadSize) ?: error("Failed to decompress payload")
							}
							else actualPayload

							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(firstPacket.type),
								payloadStream = ByteArrayInputStream(decompressedPayload),
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)

							println("$$$$ Assembled complete packet with id ${completePacket.packetId}, type: ${completePacket.type}, payload size: ${decompressedPayload.size}")
						}
					}
				}
			}
		}
	}

	private fun generateId(): Int = _nextPacketId.getAndIncrement()
}
