package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import java.util.UUID

internal class LapisBluetoothAdapterFake(
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
	override var isEnabled: Boolean,
	private val environment: FakeBluetoothEnvironment,
) : LapisBluetoothAdapter {

	var address: String = ""

	private var _name: String? = null

	override val name: String? get() = _name

	private var _isDiscovering: Boolean = false
	override val isDiscovering: Boolean get() = _isDiscovering

	override fun setName(name: String): Boolean {
		_name = name
		bluetoothEventsFake.emitDeviceName(name)
		return true
	}

	override fun startDiscovery(): Boolean {
		_isDiscovering = true
		bluetoothEventsFake.emitDiscovering(true)

		environment.getScannableDevices(address).forEach { device ->
			bluetoothEventsFake.emitDeviceFound(device)
		}

		return true
	}

	override fun cancelDiscovery(): Boolean {
		_isDiscovering = false
		bluetoothEventsFake.emitDiscovering(false)
		return true
	}

	override fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val serverSocket = LapisBluetoothServerSocketFake(
			environment = environment,
			address = address,
			serviceUuid = uuid
		)
		environment.registerServer(address, uuid, serverSocket)
		return serverSocket
	}

	override fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		return listenUsingRfcommWithServiceRecord(name, uuid)
	}

	override fun getRemoteDevice(address: String): LapisBluetoothDevice {
		return LapisBluetoothDeviceFake(
			address = address,
			name = null,
			bluetoothEventsFake = bluetoothEventsFake,
			environment = environment,
			requesterAddress = this.address
		)
	}

	override fun getBondedDevices(): List<LapisBluetoothDevice>? {
		return emptyList() // We can implement this later if needed
	}
}
