package com.elianfabian.lapisbt.abstraction

import java.io.InputStream
import java.io.OutputStream

public interface LapisBluetoothSocket {

	public val inputStream: InputStream
	public val outputStream: OutputStream
	public val isConnected: Boolean
	public val remoteDevice: LapisBluetoothDevice

	public fun connect()
	public fun close()
}
