package com.elianfabian.lapisbt.simulated

import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.Volatile

internal class SimulatedLapisBluetoothServerSocket(
	private val environment: SimulatedBluetoothEnvironment,
	private val address: String,
	private val serviceUuid: UUID,
) : LapisBluetoothServerSocket {

	private sealed interface QueueEntry {
		class Connection(val socket: LapisBluetoothSocket) : QueueEntry
		object Disconnect : QueueEntry
	}

	private val _pendingConnections = LinkedBlockingQueue<QueueEntry>()

	@Volatile
	private var _isClosed = false


	fun enqueueIncomingConnection(socket: LapisBluetoothSocket) {
		if (_isClosed) {
			return
		}
		_pendingConnections.put(QueueEntry.Connection(socket))
	}

	override fun accept(): LapisBluetoothSocket {
		if (_isClosed) {
			throw IOException("Server socket closed")
		}

		return when (val entry = _pendingConnections.take()) {
			is QueueEntry.Disconnect -> {
				throw IOException("Socket closed or cancelled")
			}
			is QueueEntry.Connection -> {
				val clientSocket = entry.socket
				if (clientSocket is SimulatedLapisBluetoothSocket) {
					clientSocket.setConnected(true)
				}
				clientSocket
			}
		}
	}

	fun cancel() {
		_pendingConnections.clear()
		_pendingConnections.offer(QueueEntry.Disconnect)
	}

	override fun close() {
		if (_isClosed) {
			return
		}
		_isClosed = true
		_pendingConnections.clear()
		_pendingConnections.offer(QueueEntry.Disconnect)
		environment.unregisterServer(address, serviceUuid)
	}
}
