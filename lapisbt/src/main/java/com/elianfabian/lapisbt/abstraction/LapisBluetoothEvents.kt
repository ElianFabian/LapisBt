package com.elianfabian.lapisbt.abstraction

import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.flow.SharedFlow

internal interface LapisBluetoothEvents {

	val bluetoothStateFlow: SharedFlow<Int>
	val deviceAliasChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceBondStateChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice>
	val deviceNameFlow: SharedFlow<String?>
	val deviceUuidsChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceFoundFlow: SharedFlow<LapisBluetoothDevice>
	val isDiscoveringFlow: SharedFlow<Boolean>
	val onActivityResumed: SharedFlow<Unit>



	fun dispose() {
		// TODO: I think this is triggered when a remote device requests pairing
		//  I want to implement it and test it later
		AndroidBluetoothDevice.ACTION_PAIRING_REQUEST
		AndroidBluetoothDevice.EXTRA_PAIRING_KEY
		AndroidBluetoothDevice.EXTRA_PAIRING_VARIANT
	}
}
