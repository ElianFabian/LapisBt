package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import com.elianfabian.lapisbt.model.BluetoothDevice

sealed interface ApiBasedBluetoothCommunicationAction {
	data object StartScan : ApiBasedBluetoothCommunicationAction
	data object StopScan : ApiBasedBluetoothCommunicationAction
	data object StartServer : ApiBasedBluetoothCommunicationAction
	data object StopServer : ApiBasedBluetoothCommunicationAction
	data object OpenBluetoothSettings : ApiBasedBluetoothCommunicationAction
	data object OpenDeviceInfoSettings : ApiBasedBluetoothCommunicationAction
	data object MakeDeviceDiscoverable : ApiBasedBluetoothCommunicationAction
//	data object SendMessage : ApiBasedBluetoothCommunicationAction
//	data class EnterMessage(val message: String) : ApiBasedBluetoothCommunicationAction
	data class ClickScannedDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data class ClickPairedDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data class PairDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data class UnpairDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data class LongClickPairedDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data class LongClickScannedDevice(val device: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data object EditBluetoothDeviceName : ApiBasedBluetoothCommunicationAction
	data object SaveBluetoothDeviceName : ApiBasedBluetoothCommunicationAction
	data class EnterBluetoothDeviceName(val bluetoothDeviceName: String) : ApiBasedBluetoothCommunicationAction
	data class CheckUseSecureConnection(val enabled: Boolean) : ApiBasedBluetoothCommunicationAction
	data class SelectTargetDeviceToMessage(val connectedDevice: BluetoothDevice) : ApiBasedBluetoothCommunicationAction
	data object SelectAllDevicesToMessage : ApiBasedBluetoothCommunicationAction
	data object EnableBluetooth : ApiBasedBluetoothCommunicationAction

	data object ClickGetMyOwnAddress : ApiBasedBluetoothCommunicationAction
	data object ClickOpenAppSettingsRemotely : ApiBasedBluetoothCommunicationAction
	data class ClickShowToastRemotely(val message: String) : ApiBasedBluetoothCommunicationAction
}
