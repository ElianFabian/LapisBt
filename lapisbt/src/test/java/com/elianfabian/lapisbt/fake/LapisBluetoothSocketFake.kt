package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LapisBluetoothSocketFake(
	override val remoteDevice: LapisBluetoothDeviceFake,
	initialIncomingData: ByteArray = ByteArray(0),
	var connectSuccess: Boolean = true,
	override val inputStream: InputStream = ByteArrayInputStream(initialIncomingData),
	override val outputStream: OutputStream = ByteArrayOutputStream(),
) : LapisBluetoothSocket {

	override var isConnected: Boolean = true

	var twin: LapisBluetoothSocketFake? = null

	private val _queue = LinkedBlockingQueue<Int>()


	override fun connect() {
		if (!connectSuccess) {
			throw Exception("Failed to connect to the Bluetooth device.")
		}
		// Simulate connection delay/handshake
		_queue.poll(100, TimeUnit.MILLISECONDS)
		isConnected = true
		remoteDevice.setConnected(true)
	}

	override fun close() {
		if (!isConnected) return
		isConnected = false
		remoteDevice.setConnected(false)

		try {
			inputStream.close()
			outputStream.close()
		} catch (_: Exception) {
			// Ignore close errors in fake
		}

		if (_queue.isEmpty()) {
			_queue.add(1)
		}

		twin?.close()
	}
}
