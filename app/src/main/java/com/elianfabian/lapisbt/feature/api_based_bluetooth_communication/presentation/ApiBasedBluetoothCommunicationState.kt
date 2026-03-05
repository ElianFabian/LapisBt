package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import com.elianfabian.lapisbt.model.BluetoothDevice

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
	val scannedDevices: List<BluetoothDevice> = emptyList(),
	val connectedDevices: List<BluetoothDevice> = emptyList(),
	val selectedDevice: SelectedDevice = SelectedDevice.None,
	val permissionDialog: PermissionDialogState? = null,
) {
	data class PermissionDialogState(
		val title: String,
		val message: String,
		val actionName: String,
		val onAction: () -> Unit,
		val onDismissRequest: () -> Unit,
	)

	sealed interface SelectedDevice {
		data class Device(val device: BluetoothDevice): SelectedDevice
		data object AllDevices : SelectedDevice
		data object None : SelectedDevice
	}
}
