package com.elianfabian.lapisbt.abstraction

internal interface LapisBluetoothServerSocket {

	fun accept(): LapisBluetoothSocket
	fun close()
}
