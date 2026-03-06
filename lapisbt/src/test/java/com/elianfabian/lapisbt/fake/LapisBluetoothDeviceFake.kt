package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal data class LapisBluetoothDeviceFake(
	override val address: String,
	override var name: String?,
	override var bondState: Int = BluetoothDevice.BOND_NONE,
	override var alias: String? = null,
	override var uuids: List<UUID>? = emptyList(),
	override val majorDeviceClass: Int = BluetoothClass.Device.Major.PHONE,
	override val addressType: Int = BluetoothDevice.ADDRESS_TYPE_UNKNOWN,
	override val type: Int = BluetoothDevice.DEVICE_TYPE_CLASSIC,
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
) : LapisBluetoothDevice {

	private val _scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	private var _isConnected: Boolean = false

	override fun createBond(): Boolean {
		bondState = AndroidBluetoothDevice.BOND_BONDING
		bluetoothEventsFake.emitDeviceBondState(this.copy())
		_scope.launch {
			delay(250)
			bondState = AndroidBluetoothDevice.BOND_BONDED
			bluetoothEventsFake.emitDeviceBondState(this@LapisBluetoothDeviceFake.copy())
		}
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
			bluetoothEventsFake.emitDeviceDisconnected(this.copy())
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
