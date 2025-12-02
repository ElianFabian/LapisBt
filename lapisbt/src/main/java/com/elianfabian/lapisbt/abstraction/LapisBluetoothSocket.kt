package com.elianfabian.lapisbt.abstraction

import java.io.InputStream
import java.io.OutputStream

internal interface LapisBluetoothSocket {

	val inputStream: InputStream
	val outputStream: OutputStream
	val isConnected: Boolean
	val remoteDevice: LapisBluetoothDevice

	fun connect()
	fun close()
}
