package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothClass
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID
import kotlin.random.Random

internal class LapisBluetoothAdapterFake(
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
	override var isEnabled: Boolean,
) : LapisBluetoothAdapter {

	private val _random = Random(1)

	private val _devices = (1..20).map {
		LapisBluetoothDeviceFake(
			address = generateAddress(),
			name = "Device $it",
			alias = "Device $it alias",
			type = listOf(
				AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC,
				AndroidBluetoothDevice.DEVICE_TYPE_LE,
				AndroidBluetoothDevice.DEVICE_TYPE_DUAL,
				//AndroidBluetoothDevice.DEVICE_TYPE_UNKNOWN,
			).random(_random),
			addressType = listOf(
				AndroidBluetoothDevice.ADDRESS_TYPE_PUBLIC,
				AndroidBluetoothDevice.ADDRESS_TYPE_RANDOM,
				AndroidBluetoothDevice.ADDRESS_TYPE_ANONYMOUS,
				//AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN,
			).random(_random),
			bondState = listOf(
				AndroidBluetoothDevice.BOND_BONDED,
				AndroidBluetoothDevice.BOND_BONDING,
				AndroidBluetoothDevice.BOND_NONE
			).random(_random),
			majorDeviceClass = listOf(
//				BluetoothClass.Device.Major.TOY,
//				BluetoothClass.Device.Major.COMPUTER,
				BluetoothClass.Device.Major.PHONE,
//				BluetoothClass.Device.Major.AUDIO_VIDEO,
//				BluetoothClass.Device.Major.HEALTH,
//				BluetoothClass.Device.Major.IMAGING,
//				BluetoothClass.Device.Major.MISC,
//				BluetoothClass.Device.Major.NETWORKING,
//				BluetoothClass.Device.Major.PERIPHERAL,
//				BluetoothClass.Device.Major.WEARABLE,
				//BluetoothClass.Device.Major.UNCATEGORIZED,
			).random(_random),
			uuids = emptyList(),
			bluetoothEventsFake = bluetoothEventsFake,
		)
	}


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

		_devices.filter {
			it.bondState != AndroidBluetoothDevice.BOND_BONDED && !it.isConnected()
		}.forEach { device ->
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
		val remoteDevice = _devices.filter { it.bondState == AndroidBluetoothDevice.BOND_BONDED }.random(_random)
		remoteDevice.bondState = AndroidBluetoothDevice.BOND_BONDED
		remoteDevice.setConnected(true)

		bluetoothEventsFake.emitDeviceBondState(remoteDevice)

		return LapisBluetoothServerSocketFake(
			remoteDevice = remoteDevice,
		)
	}

	override fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val remoteDevice = _devices.filter { it.bondState != AndroidBluetoothDevice.BOND_BONDED }.random(_random)
		remoteDevice.setConnected(true)

		return LapisBluetoothServerSocketFake(
			remoteDevice = remoteDevice,
		)
	}

	override fun getRemoteDevice(address: String): LapisBluetoothDevice {
		return _devices.first { it.address == address }
	}

	override fun getBondedDevices(): List<LapisBluetoothDevice> {
		return _devices.filter { it.bondState == AndroidBluetoothDevice.BOND_BONDED }
	}


	private fun generateAddress(): String {
		return List(6) { _random.nextInt(0, 255) }.joinToString(":") { byte -> "%02X".format(byte) }
	}
}
