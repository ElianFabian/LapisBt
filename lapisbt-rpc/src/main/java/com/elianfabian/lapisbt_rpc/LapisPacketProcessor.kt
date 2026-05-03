package com.elianfabian.lapisbt_rpc

import android.util.Log
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

public interface LapisPacketProcessor {

	public val remoteCompletePackets: Channel<CompleteBluetoothPacket>

	public suspend fun sendData(stream: OutputStream)

	public suspend fun receiveData(stream: InputStream)

	public suspend fun sendPacketData(
		type: Byte,
		payload: ByteArray,
		methodMetadata: Map<String, String>,
	)

	public fun dispose()
}


internal class DefaultLapisPacketProcessor : LapisPacketProcessor {

	private companion object {

		const val BLUETOOTH_PACKET_LENGTH = 256

		val TAG = this::class.qualifiedName!!
	}


	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _remotePacketsById = ConcurrentHashMap<UUID, MutableList<BluetoothPacket>>()

	private val _remotePacketChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)
	override val remoteCompletePackets get() = _remoteCompletePacketChannel

	private val _remoteCompletePacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)

	private val _pendingPacketToSendChannel = Channel<BluetoothPacket>(capacity = 1)


	init {
		launchPacketProcessing()
	}


	override suspend fun sendData(stream: OutputStream) {
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

			val mostSignificantBits = dataStream.readLong()
			val leastSignificantBits = dataStream.readLong()
			val id = UUID(mostSignificantBits, leastSignificantBits)

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
		methodMetadata: Map<String, String>,
	) {
		val packets = sequence {
			val uuidBytesSize = Long.SIZE_BYTES * 2
			val indexBytesSize = Int.SIZE_BYTES * 1

			val compressed = CompressionUtil.shouldCompress(payload)
			val actualPayload = if (compressed) {
				CompressionUtil.compress(payload)!!
			}
			else payload

			println("$$$ original: ${payload.size}, actual: ${actualPayload.size}")

			val remainingPayloadSize = actualPayload.size
			val fragmentPayloadSize = BLUETOOTH_PACKET_LENGTH - uuidBytesSize - indexBytesSize
			val numberOfFragments = if (remainingPayloadSize <= 0) {
				0
			}
			else (remainingPayloadSize + fragmentPayloadSize - 1) / fragmentPayloadSize

			val packetId = UUID.randomUUID()

			val firstFragment = BluetoothPacket.FirstFragment(
				packetId = packetId,
				type = type,
				length = numberOfFragments,
				compressed = compressed,
				originalPayloadSize = payload.size,
			)
			yield(firstFragment)

			for (index in 0 until numberOfFragments) {
				val start = index * fragmentPayloadSize
				val end = minOf(start + fragmentPayloadSize, actualPayload.size)
				val fragment = BluetoothPacket.Fragment(
					packetId = packetId,
					index = index,
					payload = actualPayload.sliceArray(start until end).also { println("$$$ payload size: ${it.size}, target: $fragmentPayloadSize") },
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
				packetStream.writeLong(packet.packetId.mostSignificantBits)
				packetStream.writeLong(packet.packetId.leastSignificantBits)
				packetStream.writeByte(packet.type.toInt())
				packetStream.writeInt(packet.length)
				packetStream.writeBoolean(packet.compressed)
				packetStream.writeInt(packet.originalPayloadSize)
			}
			is BluetoothPacket.Fragment -> {
				packetStream.writeLong(packet.packetId.mostSignificantBits)
				packetStream.writeLong(packet.packetId.leastSignificantBits)
				packetStream.writeInt(packet.index)
				packetStream.write(packet.payload)
			}
		}

		val bytesToWrite = byteArrayOutputStream.toByteArray()
		if (bytesToWrite.size > BLUETOOTH_PACKET_LENGTH) {
			throw IllegalStateException("The serialized first fragment packet with id ${packet.packetId} is too large to fit in a single Bluetooth packet. Size: ${bytesToWrite.size}, limit: $BLUETOOTH_PACKET_LENGTH")
		}
		if (bytesToWrite.size < BLUETOOTH_PACKET_LENGTH) {
			val paddedPacket = bytesToWrite.padded(BLUETOOTH_PACKET_LENGTH)
			dataStream.write(paddedPacket)
		}
		else {
			dataStream.write(bytesToWrite)
		}

		// I'm not sure if flush is necessary here, we'll see during testing
		//dataStream.flush()
		println("$$$$ packet sent: $packet")
	}

	private fun deserializePacket(
		stream: InputStream,
		id: UUID,
		index: Int,
	): BluetoothPacket {
		if (index == -1) {
			return deserializeFirstFragment(stream, id)
		}

		val uuidBytesSize = Long.SIZE_BYTES * 2
		val indexBytesSize = Int.SIZE_BYTES * 1

		val payload = stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH - uuidBytesSize - indexBytesSize)

		return BluetoothPacket.Fragment(
			packetId = id,
			index = index,
			payload = payload,
		)
	}

	private fun deserializeFirstFragment(stream: InputStream, id: UUID): BluetoothPacket.FirstFragment {
		val dataStream = DataInputStream(stream)

		val type = dataStream.readByte()
		val length = dataStream.readInt()
		val compressed = dataStream.readBoolean()
		val originalPayloadSize = dataStream.readInt()

		return BluetoothPacket.FirstFragment(
			packetId = id,
			type = type,
			length = length,
			compressed = compressed,
			originalPayloadSize = originalPayloadSize,
		)
	}

	private fun launchPacketProcessing() {
		_scope.launch {
			for (packet in _remotePacketChannel) {
				println("$$$$ processing packet: $packet")
				when (packet) {
					is BluetoothPacket.FirstFragment -> {
						println("$$$$ Stored first fragment with id ${packet.packetId}, type: ${packet.type}, length: ${packet.length}, original payload size: ${packet.originalPayloadSize}")
						if (packet.length == 0) {
							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(packet.type),
								payloadStream = ByteArrayInputStream(ByteArray(0)),
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)
						}
						else {
							Log.wtf(TAG, "Invalid packet length: ${packet.length} for packet with id: ${packet.packetId}")
						}
					}
					is BluetoothPacket.Fragment -> {
						println("$$$$ Stored fragment with id ${packet.packetId}, index: ${packet.index}, payload size: ${packet.payload.size}")
						val packets = _remotePacketsById[packet.packetId]!!

						val firstPacket = packets.firstOrNull() as? BluetoothPacket.FirstFragment ?: throw IllegalStateException("There should be a FirstFragment packet when processing a Fragment packet")

						if (packet.index == firstPacket.length - 1) {
							val fullPayloadBaos = ByteArrayOutputStream()
							packets.filterIsInstance<BluetoothPacket.Fragment>()
								.forEach { fullPayloadBaos.write(it.payload) }
							val fullPayload = fullPayloadBaos.toByteArray()

							val actualPayload = if (firstPacket.compressed) {
								CompressionUtil.decompress(fullPayload, firstPacket.originalPayloadSize) ?: error("Failed to decompress payload")
							}
							else fullPayload

							val completePacket = CompleteBluetoothPacket(
								packetId = packet.packetId,
								type = CompleteBluetoothPacket.Type.fromByte(firstPacket.type),
								payloadStream = ByteArrayInputStream(actualPayload),
							)
							_remoteCompletePacketChannel.send(completePacket)
							_remotePacketsById.remove(packet.packetId)

							println("$$$$ Assembled complete packet with id ${completePacket.packetId}, type: ${completePacket.type}, payload size: ${actualPayload.size}")
						}
					}
				}
			}
		}
	}
}
