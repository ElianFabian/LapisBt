package com.elianfabian.lapisbt.abstraction.impl

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import java.util.UUID

internal class LapisBluetoothAdapterImpl(
	private val adapter: BluetoothAdapter,
) : LapisBluetoothAdapter {

	override val name: String? get() = adapter.name

	override val isEnabled: Boolean get() = adapter.isEnabled

	override val isDiscovering: Boolean get() = adapter.isDiscovering


	override fun setName(name: String): Boolean {
		return adapter.setName(name)
	}

	override fun startDiscovery(): Boolean {
		return adapter.startDiscovery()
	}

	override fun cancelDiscovery(): Boolean {
		return adapter.cancelDiscovery()
	}

	override fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val socket = adapter.listenUsingRfcommWithServiceRecord(name, uuid)
		return LapisBluetoothServerSocketImpl(socket)
	}

	override fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val socket = adapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid)
		return LapisBluetoothServerSocketImpl(socket)
	}

	override fun getRemoteDevice(address: String): LapisBluetoothDevice {
		val androidDevice = adapter.getRemoteDevice(address)

		return LapisBluetoothDeviceImpl(androidDevice);
	}

	override fun getBondedDevices(): List<LapisBluetoothDevice>? {
		return adapter.bondedDevices?.map { androidDevice ->
			LapisBluetoothDeviceImpl(androidDevice)
		}
	}
}
