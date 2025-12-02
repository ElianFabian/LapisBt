package com.elianfabian.lapisbt.abstraction.impl

import android.bluetooth.BluetoothSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.io.InputStream
import java.io.OutputStream

internal class LapisBluetoothSocketImpl(
	private val socket: BluetoothSocket,
) : LapisBluetoothSocket {

	override val inputStream: InputStream get() = socket.inputStream

	override val outputStream: OutputStream get() = socket.outputStream

	override val isConnected: Boolean get() = socket.isConnected

	override val remoteDevice: LapisBluetoothDevice = LapisBluetoothDeviceImpl(socket.remoteDevice)


	override fun connect() {
		socket.connect()
	}

	override fun close() {
		socket.close()
	}
}
