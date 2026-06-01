package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice

data class ApiBasedBluetoothCommunicationState(
	val isBluetoothSupported: Boolean,
	val isBluetoothOn: Boolean,
	val useSecureConnection: Boolean,
	val currentDeviceAddress: String?,
	val enteredBluetoothDeviceName: String? = null,
	val bluetoothDeviceName: String? = null,
	val isWaitingForConnection: Boolean = false,
	val isScanning: Boolean = false,
	val pairedDevices: List<BluetoothDevice> = emptyList(),
	val scannedDevices: List<ScannedBluetoothDevice> = emptyList(),
	val connectedDevices: List<BluetoothDevice> = emptyList(),
	val selectedDevice: SelectedDevice = SelectedDevice.None,
	val permissionDialog: PermissionDialogState? = null,
	val rpcTestState: RpcTestState = RpcTestState(),
) {
	data class PermissionDialogState(
		val title: String,
		val message: String,
		val actionName: String,
		val onAction: () -> Unit,
		val onDismissRequest: () -> Unit,
	)

	data class RpcTestState(
		val logs: List<String> = emptyList(),
		val activeFlows: Set<String> = emptySet(),
		val latestValues: Map<String, String> = emptyMap(),
		val flashlightEnabled: Boolean = false,
	)

	sealed interface SelectedDevice {
		data class Device(val device: BluetoothDevice): SelectedDevice
		data object AllDevices : SelectedDevice
		data object None : SelectedDevice
	}
}
