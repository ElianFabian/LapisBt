package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket

internal class LapisBluetoothServerSocketFake(
	private val remoteDevice: LapisBluetoothDeviceFake,
) : LapisBluetoothServerSocket {

	override fun accept(): LapisBluetoothSocket {
		return LapisBluetoothSocketFake(
			remoteDevice = remoteDevice,
		)
	}

	override fun close() {
	}
}
