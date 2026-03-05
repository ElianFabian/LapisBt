package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import com.elianfabian.lapisbt.app.common.presentation.model.BluetoothMessage
import com.elianfabian.lapisbt.model.BluetoothDevice

sealed interface ManualBluetoothCommunicationAction {
	data object StartScan : ManualBluetoothCommunicationAction
	data object StopScan : ManualBluetoothCommunicationAction
	data object StartServer : ManualBluetoothCommunicationAction
	data object StopServer : ManualBluetoothCommunicationAction
	data object OpenBluetoothSettings : ManualBluetoothCommunicationAction
	data object OpenDeviceInfoSettings : ManualBluetoothCommunicationAction
	data object MakeDeviceDiscoverable : ManualBluetoothCommunicationAction
	data object SendMessage : ManualBluetoothCommunicationAction
	data class EnterMessage(val message: String) : ManualBluetoothCommunicationAction
	data class ClickScannedDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class ClickPairedDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class PairDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class UnpairDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class LongClickPairedDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class LongClickScannedDevice(val device: BluetoothDevice) : ManualBluetoothCommunicationAction
	data class ClickMessage(val message: BluetoothMessage) : ManualBluetoothCommunicationAction
	data object EditBluetoothDeviceName : ManualBluetoothCommunicationAction
	data object SaveBluetoothDeviceName : ManualBluetoothCommunicationAction
	data class EnterBluetoothDeviceName(val bluetoothDeviceName: String) : ManualBluetoothCommunicationAction
	data class CheckUseSecureConnection(val enabled: Boolean) : ManualBluetoothCommunicationAction
	data class SelectTargetDeviceToMessage(val connectedDevice: BluetoothDevice) : ManualBluetoothCommunicationAction
	data object SelectAllDevicesToMessage : ManualBluetoothCommunicationAction
	data object EnableBluetooth : ManualBluetoothCommunicationAction
}
