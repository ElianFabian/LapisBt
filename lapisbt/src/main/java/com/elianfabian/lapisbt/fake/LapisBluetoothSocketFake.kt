package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LapisBluetoothSocketFake(
	override val remoteDevice: LapisBluetoothDeviceFake,
	var connectSuccess: Boolean = true,
	override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0)),
	override val outputStream: OutputStream = ByteArrayOutputStream(),
	val isSecure: Boolean = false,
) : LapisBluetoothSocket {

	override var isConnected: Boolean = false

	var twin: LapisBluetoothSocketFake? = null

	private val _queue = LinkedBlockingQueue<Int>()

	init {
		remoteDevice.environment.registerSocket(this)
	}

	override fun connect() {
		println("$$$ connect: ${remoteDevice.address}")
		if (!connectSuccess) {
			throw IOException("Failed to connect to the Bluetooth device.")
		}

		if (isSecure) {
			// Wait for bond state to become BONDED if secure
			var attempts = 0
			while (!remoteDevice.environment.isBonded(remoteDevice.requesterAddress, remoteDevice.address) && attempts < 20) {
				Thread.sleep(100)
				attempts++
			}
			
			if (!remoteDevice.environment.isBonded(remoteDevice.requesterAddress, remoteDevice.address)) {
				throw IOException("Secure connection failed: Devices not bonded.")
			}
		}

		// Simulate connection delay/handshake
		_queue.poll(100, TimeUnit.MILLISECONDS)
		isConnected = true
		remoteDevice.setConnected(true)

		twin?.let {
			it.isConnected = true
			it.remoteDevice.setConnected(true)
		}
	}

	override fun close() {
		if (!isConnected) return
		isConnected = false
		remoteDevice.setConnected(false)
		
		remoteDevice.environment.unregisterSocket(this)

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

	internal fun setConnected(value: Boolean) {
		isConnected = value
	}
}
