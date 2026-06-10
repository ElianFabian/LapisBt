package com.elianfabian.lapisbt.simulated

import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.BidirectionalStreamPipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// TODO: in real devices we can only have one connection per device, if we try to add more
//  the previous one will be closed
//  we have to implement this behavior for the simulated environment
internal class SimulatedLapisBluetoothSocket(
	override val remoteDevice: SimulatedLapisBluetoothDevice,
	var connectSuccess: Boolean = true,
	private var _inputStream: InputStream? = null,
	private var _outputStream: OutputStream? = null,
	var isSecure: Boolean = false,
	val serviceUuid: UUID? = null,
	val isClient: Boolean = false,
) : LapisBluetoothSocket {

	override val inputStream: InputStream
		get() = _inputStream ?: ByteArrayInputStream(ByteArray(0))
	override val outputStream: OutputStream
		get() = _outputStream ?: ByteArrayOutputStream()

	override var isConnected: Boolean = false

	var twin: SimulatedLapisBluetoothSocket? = null

	private val _queue = LinkedBlockingQueue<Int>()

	private val _connectDelayMs = 500L

	@Volatile
	private var _isClosed = false


	init {
		remoteDevice.environment.registerSocket(this)
	}


	override fun connect() {
		if (_connectDelayMs > 0) {
			_queue.poll(_connectDelayMs, TimeUnit.MILLISECONDS)
		}
		if (_isClosed) {
			throw IOException("Socket is already closed.")
		}
		if (!connectSuccess) {
			throw IOException("Failed to connect to the Bluetooth device.")
		}

		if (isClient && serviceUuid != null) {
			var serverEntry: SimulatedBluetoothEnvironment.ServerEntry? = null
			var attempts = 0
			while (attempts < 50) { // 5 seconds timeout
				if (_isClosed) throw IOException("Connection cancelled.")
				serverEntry = remoteDevice.environment.getServer(remoteDevice.address, serviceUuid)
				if (serverEntry != null) break

				_queue.poll(100, TimeUnit.MILLISECONDS)
				attempts++
			}

			if (_isClosed) {
				throw IOException("Connection cancelled.")
			}

			if (serverEntry == null) {
				throw IOException("Service ${serviceUuid} not found on device ${remoteDevice.address}")
			}

			// If either side is secure, the connection is secure.
			val finalIsSecure = isSecure || serverEntry.isSecure
			this.isSecure = finalIsSecure

			if (finalIsSecure) {
				// Automatically initiate pairing if not bonded
				if (!remoteDevice.environment.isBonded(remoteDevice.requesterAddress, remoteDevice.address)) {
					println("Secure connection requested: automatically initiating pairing between ${remoteDevice.requesterAddress} and ${remoteDevice.address}")
					remoteDevice.environment.initiatePairing(remoteDevice.requesterAddress, remoteDevice.address)
				}

				var bondAttempts = 0
				while (!remoteDevice.environment.isBonded(remoteDevice.requesterAddress, remoteDevice.address) && bondAttempts < 20) {
					if (_isClosed) {
						throw IOException("Connection cancelled.")
					}
					_queue.poll(100, TimeUnit.MILLISECONDS)
					bondAttempts++
				}

				if (_isClosed) {
					throw IOException("Connection cancelled.")
				}

				if (!remoteDevice.environment.isBonded(remoteDevice.requesterAddress, remoteDevice.address)) {
					throw IOException("Secure connection failed: Devices not bonded.")
				}
			}

			val pipe = BidirectionalStreamPipe()
			this._inputStream = pipe.sideA.inputStream
			this._outputStream = pipe.sideA.outputStream

			val serverSideSocket = SimulatedLapisBluetoothSocket(
				remoteDevice = SimulatedLapisBluetoothDevice(
					address = remoteDevice.requesterAddress,
					name = remoteDevice.environment.getDeviceName(remoteDevice.requesterAddress),
					bluetoothEvents = remoteDevice.environment.getDeviceEvents(remoteDevice.address)!!,
					environment = remoteDevice.environment,
					requesterAddress = remoteDevice.address
				),
				_inputStream = pipe.sideB.inputStream,
				_outputStream = pipe.sideB.outputStream,
				isSecure = finalIsSecure,
				isClient = false
			)

			this.twin = serverSideSocket
			serverSideSocket.twin = this

			serverEntry.socket.enqueueIncomingConnection(serverSideSocket)
		}

		if (_isClosed) {
			throw IOException("Connection cancelled.")
		}

		isConnected = true
		remoteDevice.setConnected(true)

		twin?.let {
			it.isConnected = true
			it.remoteDevice.setConnected(true)
		}
	}

	override fun close() {
		if (_isClosed) {
			return
		}
		_isClosed = true

		if (_queue.isEmpty()) {
			_queue.add(1)
		}

		if (!isConnected) {
			return
		}
		isConnected = false
		remoteDevice.setConnected(false)

		remoteDevice.environment.unregisterSocket(this)

		try {
			_inputStream?.close()
			_outputStream?.close()
		}
		catch (_: Exception) {
		}

		twin?.close()
	}

	internal fun setConnected(value: Boolean) {
		isConnected = value
	}
}
