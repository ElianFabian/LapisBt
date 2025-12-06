package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LapisBluetoothServerSocketFake(
	private val remoteDevice: LapisBluetoothDeviceFake,
) : LapisBluetoothServerSocket {

	private val queue = LinkedBlockingQueue<Int>()

	// TODO: I think we should be able to decide when to throw a IOException
	override fun accept(): LapisBluetoothSocket {
		// TODO: I think we should also be able to set the timeout outside
		queue.poll(2, TimeUnit.SECONDS)
		return LapisBluetoothSocketFake(
			remoteDevice = remoteDevice,
		)
	}

	override fun close() {
		if (queue.isEmpty()) {
			queue.add(1)
		}
	}
}
