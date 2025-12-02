package com.elianfabian.lapisbt.abstraction.impl

import android.os.Build
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID

internal class LapisBluetoothDeviceImpl(
	private val device: AndroidBluetoothDevice,
) : LapisBluetoothDevice {

	override val address: String get() = device.address

	override val name: String? get() = device.name

	override val alias: String?
		get() = if (Build.VERSION.SDK_INT >= 30) {
			device.alias
		}
		else device.name

	override val uuids: List<UUID> get() = device.uuids.map { it.uuid }

	override val majorDeviceClass: Int
		get() = if (Build.VERSION.SDK_INT >= 35) {
			device.bluetoothClass.majorDeviceClass
		}
		else -1

	override val addressType: Int
		get() = if (Build.VERSION.SDK_INT >= 35) {
			device.addressType
		}
		else -1

	override val type: Int
		get() = device.type

	override val bondState: Int
		get() = device.bondState

	override fun createRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		val bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
		return LapisBluetoothSocketImpl(bluetoothSocket)
	}

	override fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		val bluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
		return LapisBluetoothSocketImpl(bluetoothSocket)
	}

	override fun createBond(): Boolean {
		return device.createBond()
	}

	override fun removeBond(): Boolean {
		try {
			val removeBondMethod = device.javaClass.getMethod("removeBond")

			return removeBondMethod.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}
}
