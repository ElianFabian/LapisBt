package com.elianfabian.lapisbt.fake

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothStatusCodes
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("NewApi")
internal data class LapisBluetoothDeviceFake(
	override val address: String,
	override var name: String?,
	override var bondState: Int = AndroidBluetoothDevice.BOND_NONE,
	override var alias: String? = null,
	override var uuids: List<UUID>? = emptyList(),
	override val deviceClass: Int = BluetoothClass.Device.PHONE_SMART,
	override val majorDeviceClass: Int = BluetoothClass.Device.Major.PHONE,

	override val addressType: Int = AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN,
	override val type: Int = AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC,
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
	internal val environment: FakeBluetoothEnvironment,
	var requesterAddress: String = "",
) : LapisBluetoothDevice {

	private val _scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	private var _isConnected: Boolean = false

	private var _bondingJob: Job? = null

	override fun createBond(): Boolean {
		val config = environment.getDeviceConfig(address)
		val pairingResult = config?.pairingResult ?: FakeBluetoothConfiguration.PairingResult.Success

		bondState = AndroidBluetoothDevice.BOND_BONDING
		bluetoothEventsFake.emitDeviceBondState(this.copy())

		_bondingJob = _scope.launch {
			delay(250)
			if (pairingResult is FakeBluetoothConfiguration.PairingResult.Failure) {
				bondState = AndroidBluetoothDevice.BOND_NONE
				bluetoothEventsFake.emitDeviceBondState(this@LapisBluetoothDeviceFake.copy())
				bluetoothEventsFake.emitUnbondReason(
					LapisBluetoothEvents.UnbondReasonEvent(
						androidDevice = this@LapisBluetoothDeviceFake.copy(),
						reason = pairingResult.reason
					)
				)
			}
			else {
				environment.bondDevices(requesterAddress, address)
				bondState = AndroidBluetoothDevice.BOND_BONDED
				bluetoothEventsFake.emitDeviceBondState(this@LapisBluetoothDeviceFake.copy())
			}
		}
		return true
	}

	override fun setAlias(alias: String?): Int {
		this.alias = alias
		return BluetoothStatusCodes.SUCCESS
	}

	override fun setPin(pin: ByteArray): Boolean {
		return false
	}

	override fun removeBond(): Boolean {
		environment.unpairDeviceLocally(requesterAddress, address)
		bondState = AndroidBluetoothDevice.BOND_NONE
		bluetoothEventsFake.emitDeviceBondState(this.copy())
		return true
	}

	override fun cancelBondProcess(): Boolean {
		_bondingJob?.cancel()

		return true
	}

	override fun isBondingInitiatedLocally(): Boolean {
		return false
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
		return environment.requestConnection(requesterAddress, address, uuid, isSecureRequest = true) ?: LapisBluetoothSocketFake(
			remoteDevice = this,
			connectSuccess = false
		)
	}

	override fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket {
		return environment.requestConnection(requesterAddress, address, uuid, isSecureRequest = false) ?: LapisBluetoothSocketFake(
			remoteDevice = this,
			connectSuccess = false
		)
	}
}
