package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LapisBluetoothServerSocketFake(
	private val environment: FakeBluetoothEnvironment,
	private val address: String,
	private val serviceUuid: UUID,
) : LapisBluetoothServerSocket {

	private val pendingConnections = LinkedBlockingQueue<LapisBluetoothSocket>()
	private var isClosed = false

	/**
	 * Enqueues a socket to be returned by [accept].
	 */
	fun enqueueIncomingConnection(socket: LapisBluetoothSocket) {
		pendingConnections.put(socket)
	}

	override fun accept(): LapisBluetoothSocket {
		if (isClosed) {
			throw IOException("Server socket closed")
		}

		// Wait for a connection to be enqueued by the environment
		val clientSocket = pendingConnections.poll(Long.MAX_VALUE, TimeUnit.SECONDS)
			?: throw IOException("Accept timed out")

		if (clientSocket is LapisBluetoothSocketFake) {
			println("$$$ serverSocket finished: $address")
			clientSocket.setConnected(true)
		}

		return clientSocket
	}

	override fun close() {
		if (isClosed) return
		isClosed = true
		pendingConnections.clear()
		environment.unregisterServer(address, serviceUuid)
	}
}
