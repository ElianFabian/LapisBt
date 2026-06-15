package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.util.LapisLogger
import com.elianfabian.lapisbt_rpc.exception.LapisEncryptionException
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

public interface LapisPacketProcessor {

	public val remoteCompletePackets: Channel<CompleteBluetoothPacket>

	public suspend fun sendData(stream: OutputStream)

	public suspend fun receiveData(stream: InputStream)

	public suspend fun sendPacketData(
		type: Byte,
		payload: ByteArray,
		methodMetadataAnnotations: List<Annotation>,
	)

	public var encryption: LapisEncryption?

	public var encryptionRequired: Boolean

	public fun dispose()
}


internal class DefaultLapisPacketProcessor(
	private val logger: LapisLogger,
) : LapisPacketProcessor {

	internal companion object {

		const val BLUETOOTH_PACKET_LENGTH = 256

		const val PACKET_ID_SIZE = Int.SIZE_BYTES
		const val INDEX_METADATA_SIZE = Int.SIZE_BYTES

		// FirstFragment: packetId (4) + type (1) + length (4) + compressed (1) + encrypted (1) + originalPayloadSize (4) + actualPayloadSize (4) = 19
		const val FIRST_FRAGMENT_METADATA_SIZE = PACKET_ID_SIZE + 1 + 4 + 1 + 1 + 4 + 4
		const val FIRST_FRAGMENT_PAYLOAD_CAPACITY = BLUETOOTH_PACKET_LENGTH - FIRST_FRAGMENT_METADATA_SIZE

		// Fragment: packetId (4) + index (4) = 8
		const val FRAGMENT_METADATA_SIZE = PACKET_ID_SIZE + INDEX_METADATA_SIZE
		const val FRAGMENT_PAYLOAD_CAPACITY = BLUETOOTH_PACKET_LENGTH - FRAGMENT_METADATA_SIZE

		val TAG = DefaultLapisPacketProcessor::class.simpleName!!
	}


	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _remotePacketsById = ConcurrentHashMap<Int, MutableList<BluetoothPacket>>()

	private val _remotePacketChannel = Channel<BluetoothPacket>(capacity = Channel.UNLIMITED)

	private val _remoteCompletePacketChannel = Channel<CompleteBluetoothPacket>(capacity = Channel.UNLIMITED)
	override val remoteCompletePackets get() = _remoteCompletePacketChannel

	private val _pendingPacketToSendChannel = Channel<BluetoothPacket>(capacity = 1)

	private val _nextPacketId = AtomicInteger(0)

	override var encryption: LapisEncryption? = null

	override var encryptionRequired: Boolean = false

	@Volatile
	private var _isDisposed = false


	init {
		launchPacketProcessing()
	}


	override suspend fun sendData(stream: OutputStream) = withContext(Dispatchers.IO) {
		checkIsNotDisposed()

		for (packetToSend in _pendingPacketToSendChannel) {
			if (_isDisposed) {
				break
			}
			serializePacket(
				stream = stream,
				packet = packetToSend,
			)
		}
	}

	override suspend fun receiveData(stream: InputStream) = withContext(Dispatchers.IO) {
		checkIsNotDisposed()

		logger.debug(TAG, "LapisPacketProcessor: Starting data reception loop...")

		while (!_isDisposed) {
			val bytes = try {
				stream.readNBytesCompat(BLUETOOTH_PACKET_LENGTH)
			}
			catch (e: IOException) {
				logger.error(TAG, "LapisPacketProcessor: Read error during data reception", e)
				break
			}

			if (bytes.isEmpty()) {
				logger.debug(TAG, "LapisPacketProcessor: End of stream reached during reception")
				break
			}
			if (bytes.size < BLUETOOTH_PACKET_LENGTH) {
				logger.warning(TAG, "LapisPacketProcessor: Received incomplete packet (${bytes.size} bytes). Discarding...")
				break
			}

			val dataStream = DataInputStream(ByteArrayInputStream(bytes))

			val id = dataStream.readInt()

			logger.verbose(TAG, "LapisPacketProcessor: Received raw fragment for ID $id (${bytes.size} bytes)")
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
		if (_isDisposed) {
			return
		}
		//checkIsNotDisposed()

		val packets = sequence {
			val compressed = CompressionUtil.shouldCompress(payload)
			var actualPayload = if (compressed) {
				CompressionUtil.compress(payload)!!
			}
			else payload

			val encrypted = encryption != null && type != CompleteBluetoothPacket.Type.Handshake.byteValue
			if (encrypted) {
				actualPayload = try {
					encryption!!.encrypt(actualPayload)
				}
				catch (e: GeneralSecurityException) {
					throw LapisEncryptionException("Failed to encrypt packet payload", e)
				}
			}

			logger.verbose(TAG, "LapisPacketProcessor: Packet payload stats - Original: ${payload.size}, Processed: ${actualPayload.size}, Encrypted: $encrypted")

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
				encrypted = encrypted,
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
					payload = actualPayload.sliceArray(start until end).also { logger.verbose(TAG, "LapisPacketProcessor: Fragmentation: index $index, size ${it.size}") },
				)
				yield(fragment)
			}
		}

		packets.forEach { packet ->
			logger.verbose(TAG, "LapisPacketProcessor: Fragment queued for transmission: $packet")
			_pendingPacketToSendChannel.send(packet)
		}
	}

	override fun dispose() {
		if (_isDisposed) {
			return
		}
		_isDisposed = true
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
				packetStream.writeBoolean(packet.encrypted)
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

		logger.verbose(TAG, "LapisPacketProcessor: Fragment transmitted successfully: $packet")
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
		val encrypted = dataStream.readBoolean()
		val originalPayloadSize = dataStream.readInt()
		val actualPayloadSize = dataStream.readInt()

		val payloadSize = minOf(actualPayloadSize, FIRST_FRAGMENT_PAYLOAD_CAPACITY)
		val payload = dataStream.readNBytesCompat(payloadSize)

		return BluetoothPacket.FirstFragment(
			packetId = id,
			type = type,
			length = length,
			compressed = compressed,
			encrypted = encrypted,
			originalPayloadSize = originalPayloadSize,
			actualPayloadSize = actualPayloadSize,
			payload = payload,
		)
	}

	private fun launchPacketProcessing() {
		_scope.launch {
			for (packet in _remotePacketChannel) {
				if (_isDisposed) {
					break
				}
				logger.verbose(TAG, "LapisPacketProcessor: Reassembling fragment: $packet")
				ensureActive()
				when (packet) {
					is BluetoothPacket.FirstFragment -> {
						logger.verbose(TAG, "LapisPacketProcessor: Received first fragment for packet ${packet.packetId} (Type: ${packet.type})")
						try {
							if (packet.length == 0) {
								var actualPayload = packet.payload

								val packetType = CompleteBluetoothPacket.Type.fromByte(packet.type)
								if (packet.encrypted) {
									val enc = encryption ?: run {
										val ex = LapisEncryptionException("Received encrypted packet but no encryption is set")
										_remoteCompletePacketChannel.close(ex)
										throw ex
									}
									actualPayload = try {
										enc.decrypt(actualPayload)
									}
									catch (e: GeneralSecurityException) {
										val ex = LapisEncryptionException("Failed to decrypt packet payload", e)
										_remoteCompletePacketChannel.close(ex)
										throw ex
									}
								}
								else if (encryptionRequired && packetType != CompleteBluetoothPacket.Type.Handshake) {
									val ex = LapisEncryptionException("Received plaintext packet but encryption is required")
									_remoteCompletePacketChannel.close(ex)
									throw ex
								}

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

								logger.debug(TAG, "LapisPacketProcessor: Successfully reassembled packet ${completePacket.packetId} (${decompressedPayload.size} bytes)")
							}
						}
						catch (e: Exception) {
							if (e is CancellationException) throw e
							logger.error(TAG, "Error reassembling first fragment", e)
							_remoteCompletePacketChannel.close(e)
						}
					}
					is BluetoothPacket.Fragment -> {
						logger.verbose(TAG, "LapisPacketProcessor: Received fragment ${packet.index} for packet ${packet.packetId}")
						try {
							val packets = _remotePacketsById[packet.packetId] ?: return@launch

							val firstPacket = packets.firstOrNull() as? BluetoothPacket.FirstFragment ?: throw IllegalStateException("There should be a FirstFragment packet when processing a Fragment packet")

							if (packet.index == firstPacket.length - 1) {
								val fullPayloadBaos = ByteArrayOutputStream()
								fullPayloadBaos.write(firstPacket.payload)
								packets.filterIsInstance<BluetoothPacket.Fragment>()
									.sortedBy { it.index }
									.forEach { fullPayloadBaos.write(it.payload) }

								val fullPayload = fullPayloadBaos.toByteArray()
								var actualPayload = fullPayload.copyOfRange(0, firstPacket.actualPayloadSize)

								val packetType = CompleteBluetoothPacket.Type.fromByte(firstPacket.type)
								if (firstPacket.encrypted) {
									val enc = encryption ?: run {
										val ex = LapisEncryptionException("Received encrypted packet but no encryption is set")
										_remoteCompletePacketChannel.close(ex)
										throw ex
									}
									actualPayload = try {
										enc.decrypt(actualPayload)
									}
									catch (e: GeneralSecurityException) {
										val ex = LapisEncryptionException("Failed to decrypt packet payload", e)
										_remoteCompletePacketChannel.close(ex)
										throw ex
									}
								}
								else if (encryptionRequired && packetType != CompleteBluetoothPacket.Type.Handshake) {
									val ex = LapisEncryptionException("Received plaintext packet but encryption is required")
									_remoteCompletePacketChannel.close(ex)
									throw ex
								}

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

								logger.debug(TAG, "LapisPacketProcessor: Successfully reassembled packet ${completePacket.packetId} (${decompressedPayload.size} bytes)")
							}
						}
						catch (e: Exception) {
							if (e is CancellationException) throw e
							logger.error(TAG, "Error reassembling fragment", e)
							_remoteCompletePacketChannel.close(e)
						}
					}
				}
			}
		}
	}

	private fun generateId(): Int = _nextPacketId.getAndIncrement()

	private fun checkIsNotDisposed() {
		check(!_isDisposed) {
			"Can't call a method on a disposed instance"
		}
	}
}
