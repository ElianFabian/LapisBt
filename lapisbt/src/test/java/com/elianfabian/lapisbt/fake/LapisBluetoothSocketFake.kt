package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class LapisBluetoothSocketFake(
	override val remoteDevice: LapisBluetoothDeviceFake,
	initialIncomingData: ByteArray = ByteArray(0),
	var connectSuccess: Boolean = true,
) : LapisBluetoothSocket {

	override val inputStream: InputStream = ByteArrayInputStream(initialIncomingData)
	override val outputStream: OutputStream = ByteArrayOutputStream()
	override var isConnected: Boolean = false


	override fun connect() {
		outputStream
		if (!connectSuccess) {
			throw Exception("Failed to connect to the Bluetooth device.")
		}
		isConnected = true
		remoteDevice.setConnected(true)
	}

	override fun close() {
		isConnected = false
		remoteDevice.setConnected(false)
	}
}
