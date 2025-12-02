package com.elianfabian.lapisbt.abstraction.impl

import android.bluetooth.BluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket

internal class LapisBluetoothServerSocketImpl(
	private val socket: BluetoothServerSocket,
) : LapisBluetoothServerSocket {

	override fun accept(): LapisBluetoothSocket {
		val socket = socket.accept()

		return LapisBluetoothSocketImpl(socket)
	}

	override fun close() {
		socket.close()
	}
}
