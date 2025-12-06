package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID

internal data class LapisBluetoothDeviceFake(
	override val address: String,
	override var name: String?,
	override var alias: String?,
	override var uuids: List<UUID>?,
	override val majorDeviceClass: Int,
	override val addressType: Int,
	override val type: Int,
	override var bondState: Int,
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
) : LapisBluetoothDevice {

	private var _isConnected: Boolean = false

	override fun createBond(): Boolean {
		bondState = AndroidBluetoothDevice.BOND_BONDING
		bluetoothEventsFake.emitDeviceBondState(this.copy())
		bondState = AndroidBluetoothDevice.BOND_BONDED
		bluetoothEventsFake.emitDeviceBondState(this.copy())
		return true
	}

	override fun removeBond(): Boolean {
		bondState = AndroidBluetoothDevice.BOND_NONE
		bluetoothEventsFake.emitDeviceBondState(this.copy())
		return true
	}

	override fun isConnected(): Boolean {
		return _isConnected
	}

	override fun isEncrypted(): Boolean {
		return true
	}

	fun setConnected(connected: Boolean) {
		_isConnected = connected

		if (!connected) {
			bluetoothEventsFake.emitDeviceDisconnected(this)
		}
	}

	override fun createRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		if (uuids == null) {
			uuids = listOf(uuid)
		}
		else if (uuids.orEmpty().isEmpty()) {
			uuids = uuids?.plus(uuid)
		}

		if (bondState != AndroidBluetoothDevice.BOND_BONDED) {
			createBond()
		}

		return LapisBluetoothSocketFake(
			remoteDevice = this,
		)
	}

	override fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		if (uuids == null) {
			uuids = listOf(uuid)
		}
		else if (uuids.orEmpty().isEmpty()) {
			uuids = uuids?.plus(uuid)
		}

		return LapisBluetoothSocketFake(
			remoteDevice = this,
		)
	}
}
