package com.elianfabian.lapisbt.simulated

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothStatusCodes
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID

@SuppressLint("NewApi")
internal data class SimulatedLapisBluetoothDevice(
	override val address: String,
	override var name: String?,
	override var bondState: Int = AndroidBluetoothDevice.BOND_NONE,
	override var alias: String? = null,
	override var uuids: List<UUID>? = emptyList(),
	override val deviceClass: Int = BluetoothClass.Device.PHONE_SMART,
	override val majorDeviceClass: Int = BluetoothClass.Device.Major.PHONE,

	override val addressType: Int = AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN,
	override val type: Int = AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC,
	private val bluetoothEvents: SimulatedLapisBluetoothEvents,
	internal val environment: SimulatedBluetoothEnvironment,
	var requesterAddress: String = "",
) : LapisBluetoothDevice {

	private var _isConnected: Boolean = false

	override fun createBond(): Boolean {
		environment.initiatePairing(requesterAddress, address)
		return true
	}

	override fun setAlias(alias: String?): Int {
		this.alias = alias
		return BluetoothStatusCodes.SUCCESS
	}

	override fun setPin(pin: ByteArray): Boolean {
		return false
	}

	override fun fetchUuidsWithSdp(): Boolean {
		// I'm not sure how to fake this or if it's actually needed for testing, so for now it just does nothing and returns false to indicate failure.
		return false
	}

	override fun removeBond(): Boolean {
		environment.unpairDeviceLocally(requesterAddress, address)
		bondState = AndroidBluetoothDevice.BOND_NONE
		bluetoothEvents.emitDeviceBondState(this.copy())
		return true
	}

	override fun cancelBondProcess(): Boolean {
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
			bluetoothEvents.emitDeviceDisconnected(this.copy())
		}
	}

	override fun createRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		return environment.requestConnection(requesterAddress, address, uuid, isSecureRequest = true) ?: SimulatedLapisBluetoothSocket(
			remoteDevice = this,
			connectSuccess = false,
		)
	}

	override fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		return environment.requestConnection(requesterAddress, address, uuid, isSecureRequest = false) ?: SimulatedLapisBluetoothSocket(
			remoteDevice = this,
			connectSuccess = false,
		)
	}
}
