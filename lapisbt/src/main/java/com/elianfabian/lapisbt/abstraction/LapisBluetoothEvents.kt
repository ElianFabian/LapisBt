package com.elianfabian.lapisbt.abstraction

import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

internal interface LapisBluetoothEvents {

	val bluetoothStateFlow: SharedFlow<Int>
	val deviceAliasChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceBondStateChangeFlow: SharedFlow<LapisBluetoothDevice>
	val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice>
	val unbondReasonFlow: SharedFlow<UnbondReasonEvent>
	val deviceNameFlow: SharedFlow<String?>
	val deviceUuidsChangedFlow: SharedFlow<UuidsChangedEvent>
	val deviceFoundFlow: SharedFlow<DeviceFoundEvent>
	val isDiscoveringFlow: SharedFlow<Boolean>
	val scanModeFlow: SharedFlow<Int>
	val pairingRequestFlow: SharedFlow<PairingRequestEvent>
	val onActivityResumed: SharedFlow<Unit>


	fun dispose()


	data class DeviceFoundEvent(
		val androidDevice: LapisBluetoothDevice,
		val rssi: Short,
	)

	data class PairingRequestEvent(
		val androidDevice: LapisBluetoothDevice,
		val pairingKey: Int,
		val pairingVariant: Int,
	)

	data class UnbondReasonEvent(
		val androidDevice: LapisBluetoothDevice,
		val reason: Int,
	)

	data class UuidsChangedEvent(
		val androidDevice: LapisBluetoothDevice,
		val uuids: List<UUID>?,
		val isTimeout: Boolean,
	)
}
