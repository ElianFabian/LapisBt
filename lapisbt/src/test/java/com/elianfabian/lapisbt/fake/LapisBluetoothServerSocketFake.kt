package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.util.UUID

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
