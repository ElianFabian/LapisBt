package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelection
import com.elianfabian.lapisbt.app.common.presentation.component.PermissionDialogState
import com.elianfabian.lapisbt.app.common.presentation.model.BluetoothMessage
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice

data class ManualBluetoothCommunicationState(
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
	val deviceSelection: DeviceSelection = DeviceSelection.None,
	val permissionDialog: PermissionDialogState? = null,
	val messages: List<BluetoothMessage> = emptyList(),
	val enteredMessage: String = "",
)
