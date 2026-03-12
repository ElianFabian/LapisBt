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


	fun dispose()
}
