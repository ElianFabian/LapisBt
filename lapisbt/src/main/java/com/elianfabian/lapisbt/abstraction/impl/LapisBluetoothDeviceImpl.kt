package com.elianfabian.lapisbt.abstraction.impl

import android.os.Build
import androidx.annotation.RequiresApi
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
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
		else null

	override val uuids: List<UUID>? get() = device.uuids?.map { it.uuid }

	override val deviceClass: Int
		get() = device.bluetoothClass.deviceClass

	override val majorDeviceClass: Int
		get() = device.bluetoothClass.majorDeviceClass

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
		// Requires API level 19
		return device.createBond()
	}

	@RequiresApi(31)
	override fun setAlias(alias: String?): Int {
		return device.setAlias(alias)
	}

	override fun setPin(pin: ByteArray): Boolean {
		return device.setPin(pin)
	}

	@InternalBluetoothReflectionApi
	override fun removeBond(): Boolean {
		try {
			val method = device.javaClass.getMethod("removeBond")

			return method.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}

	@InternalBluetoothReflectionApi
	override fun cancelBondProcess(): Boolean {
		try {
			val method = device.javaClass.getMethod("cancelBondProcess")

			return method.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}

	@InternalBluetoothReflectionApi
	override fun isBondingInitiatedLocally(): Boolean {
		try {
			val method = device.javaClass.getMethod("isBondingInitiatedLocally")

			return method.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}

	@InternalBluetoothReflectionApi
	override fun isConnected(): Boolean {
		try {
			val method = device.javaClass.getMethod("isConnected")

			return method.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}

	// TODO: Test this method
	@InternalBluetoothReflectionApi
	override fun isEncrypted(): Boolean {
		try {
			val method = device.javaClass.getMethod("isEncrypted")

			return method.invoke(device) as Boolean
		}
		catch (_: Exception) {
			return false
		}
	}

	override fun toString(): String {
		return "LapisBluetoothDeviceImpl(address='$address', name=$name, alias=$alias, uuids=$uuids, deviceClass=$deviceClass, majorDeviceClass=$majorDeviceClass, addressType=$addressType, type=$type, bondState=$bondState)"
	}
}
