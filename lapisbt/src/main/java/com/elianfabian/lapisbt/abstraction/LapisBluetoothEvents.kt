package com.elianfabian.lapisbt.abstraction

import kotlinx.coroutines.flow.SharedFlow

internal interface LapisBluetoothEvents {

	val bluetoothStateFlow: SharedFlow<Int>
	val deviceAliasChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceBondStateChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice>
	val unbondReasonFlow: SharedFlow<UnbondReasonEvent>
	val deviceNameFlow: SharedFlow<String?>
	val deviceUuidsChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceFoundFlow: SharedFlow<LapisBluetoothDevice>
	val isDiscoveringFlow: SharedFlow<Boolean>
	val pairingRequestFlow: SharedFlow<PairingRequestEvent>
	val onActivityResumed: SharedFlow<Unit>


	fun dispose()


	data class PairingRequestEvent(
		val androidDevice: LapisBluetoothDevice,
		val pairingKey: Int,
		val pairingVariant: Int,
	)

	data class UnbondReasonEvent(
		val androidDevice: LapisBluetoothDevice,
		val reason: Int,
	)
}
