package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import android.os.Build
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.app.common.domain.MultiplePermissionController
import com.elianfabian.lapisbt.app.common.domain.NotificationController
import com.elianfabian.lapisbt.app.common.domain.PermissionController
import com.elianfabian.lapisbt.app.common.domain.PermissionState
import com.elianfabian.lapisbt.app.common.domain.StorageController
import com.elianfabian.lapisbt.app.common.domain.allArePermanentlyDenied
import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelection
import com.elianfabian.lapisbt.app.common.presentation.component.PermissionDialogState
import com.elianfabian.lapisbt.app.common.presentation.model.BluetoothMessage
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ManualBluetoothCommunicationViewModel(
	private val lapisBt: LapisBt,
	private val bluetoothPermissionController: MultiplePermissionController,
	private val accessFineLocationPermissionController: PermissionController,
	private val postNotificationsPermissionController: PermissionController,
	private val notificationController: NotificationController,
	private val androidHelper: AndroidHelper,
	private val storageController: StorageController,
) : ScopedServices.Registered {

	private val _scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	override fun onServiceRegistered() {
		println("$$$ ManualBluetoothCommunicationViewModel created")
		lapisBt.stopScan()

		_scope.launch {
			storageController.getBluetoothAddress()?.also { address ->
				_currentDeviceAddress.value = address
			}
		}

		_scope.launch {
			postNotificationsPermissionController.request()
		}

		_scope.launch {
			lapisBt.state.collect { state ->
				if (state == LapisBt.BluetoothState.Off) {
					_enteredBluetoothDeviceName.value = null
				}
			}
		}

		_scope.launch {
			lapisBt.events.collect { event ->
				println("$$$ Received Bluetooth event: $event")
				when (event) {
					is LapisBt.Event.OnDeviceConnected -> {
						println("$$$ Device connected event received for device: ${event.device}")
						if (_selectedDevice.value == DeviceSelection.None) {
							_selectedDevice.value = DeviceSelection.Device(event.device)
						}
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						androidHelper.showToast("Device disconnected: '${event.device.name}'")

						_selectedDevice.update { selection ->
							when (selection) {
								is DeviceSelection.AllDevices -> {
									val connectedDevices = lapisBt.pairedDevices.value.filter {
										it.connectionState == BluetoothDevice.ConnectionState.Connected
									}
									if (connectedDevices.isNotEmpty()) {
										DeviceSelection.AllDevices
									}
									else DeviceSelection.None
								}
								is DeviceSelection.Device -> {
									DeviceSelection.None
								}
								is DeviceSelection.None -> {
									DeviceSelection.None
								}
							}
						}
					}
					else -> Unit
				}
			}
		}
	}

	private val _currentDeviceAddress = MutableStateFlow<String?>(null)
	private val _permissionDialog = MutableStateFlow<PermissionDialogState?>(null)
	private val _messages = MutableStateFlow<List<BluetoothMessage>>(emptyList())
	private val _enteredMessage = MutableStateFlow("")
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(false)
	private val _selectedDevice = MutableStateFlow<DeviceSelection>(DeviceSelection.None)

	val state = combineTuple(
		lapisBt.pairedDevices,
		lapisBt.isScanning,
		lapisBt.state,
		_permissionDialog,
		lapisBt.bluetoothDeviceName,
		_messages,
		_enteredMessage,
		lapisBt.activeBluetoothServersUuids.map { it.isNotEmpty() },
		_enteredBluetoothDeviceName,
		_useSecureConnection,
		_selectedDevice,
		_currentDeviceAddress,
		lapisBt.scannedDevices,
		lapisBt.connectedDevices,
	).map { (
		pairedDevices,
		isScanning,
		bluetoothState,
		permissionDialog,
		bluetoothDeviceName,
		messages,
		enteredMessage,
		isWaitingForConnection,
		enteredBluetoothDeviceName,
		useSecureConnection,
		selectedDevice,
		currentDeviceAddress,
		scannedDevices,
		connectedDevices,
	) ->
		ManualBluetoothCommunicationState(
			pairedDevices = pairedDevices,
			isScanning = isScanning,
			isBluetoothOn = bluetoothState.isOn,
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothDeviceName,
			messages = messages,
			enteredMessage = enteredMessage,
			isWaitingForConnection = isWaitingForConnection,
			enteredBluetoothDeviceName = enteredBluetoothDeviceName,
			useSecureConnection = useSecureConnection,
			deviceSelection = selectedDevice,
			currentDeviceAddress = currentDeviceAddress,
			scannedDevices = scannedDevices,
			connectedDevices = connectedDevices,
			isBluetoothSupported = lapisBt.isBluetoothClassicSupported,
		)
	}.stateIn(
		scope = _scope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = ManualBluetoothCommunicationState(
			isBluetoothSupported = lapisBt.isBluetoothClassicSupported,
			isBluetoothOn = lapisBt.state.value.isOn,
			useSecureConnection = _useSecureConnection.value,
			currentDeviceAddress = null,
		),
	)

	fun sendAction(action: ManualBluetoothCommunicationAction) {
		println("$$$ Received action: $action")
		when (action) {
			is ManualBluetoothCommunicationAction.StartScan -> {
				_scope.launch {
					if (Build.VERSION.SDK_INT < 31) {
						val result = accessFineLocationPermissionController.request()
						if (result == PermissionState.PermanentlyDenied) {
							_permissionDialog.value = PermissionDialogState(
								title = "Permission Denied",
								message = "Please, enable location permissions in settings to allow scanning.",
								actionName = "Settings",
								onAction = {
									androidHelper.openAppSettings()
									_permissionDialog.value = null
								},
								onDismissRequest = {
									_permissionDialog.value = null
								},
							)
							return@launch
						}
						if (!result.isGranted) {
							androidHelper.showToast("Location permission is needed to scan")
							return@launch
						}
					}
					requestPermissionsBeforeExecuting {
						if (!lapisBt.startScan()) {
							if (androidHelper.openLocationSettings()) {
								lapisBt.clearScannedDevices()
								lapisBt.startScan()
							}
							else {
								androidHelper.showToast("Location is needed for scan to work")
							}
						}
						else {
							lapisBt.clearScannedDevices()
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.StopScan -> {
				lapisBt.stopScan()
			}
			is ManualBluetoothCommunicationAction.StartServer -> {
				_scope.launch {
					requestPermissionsBeforeExecuting {
						val result = if (_useSecureConnection.value) {
							lapisBt.startBluetoothServer(
								serviceName = ConnectionName,
								serviceUuid = ConnectionUuid,
							)
						}
						else {
							lapisBt.startBluetoothServerWithoutPairing(
								serviceName = ConnectionName,
								serviceUuid = ConnectionUuid,
							)
						}
						when (result) {
							is LapisBt.ConnectionResult.ConnectionEstablished -> {
							}
							else -> Unit
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.StopServer -> {
				lapisBt.stopBluetoothServer(ConnectionUuid)
			}
			is ManualBluetoothCommunicationAction.OpenBluetoothSettings -> {
				androidHelper.openBluetoothSettings()
			}
			is ManualBluetoothCommunicationAction.OpenDeviceInfoSettings -> {
				androidHelper.openDeviceInfoSettings()
			}
			is ManualBluetoothCommunicationAction.MakeDeviceDiscoverable -> {
				_scope.launch {
					requestPermissionsBeforeExecuting(enableBluetooth = false) {
						androidHelper.showMakeDeviceDiscoverableDialog(seconds = 300)
					}
				}
			}
			is ManualBluetoothCommunicationAction.SendMessage -> {
				_scope.launch {
					sendMessage(_enteredMessage.value)
				}
			}
			is ManualBluetoothCommunicationAction.EnterMessage -> {
				_enteredMessage.value = action.message
			}
			is ManualBluetoothCommunicationAction.ClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = DeviceSelection.Device(action.device)
					return
				}
				_scope.launch {
					if (action.device.connectionState == BluetoothDevice.ConnectionState.Connecting) {
						lapisBt.cancelConnectionAttempt(action.device.address)
						return@launch
					}
					val result = if (_useSecureConnection.value) {
						lapisBt.connectToDevice(
							deviceAddress = action.device.address,
							serviceUuid = ConnectionUuid,
						)
					}
					else {
						lapisBt.connectToDeviceWithoutPairing(
							deviceAddress = action.device.address,
							serviceUuid = ConnectionUuid,
						)
					}
					when (result) {
						is LapisBt.ConnectionResult.ConnectionEstablished -> {
						}
						else -> Unit
					}
				}
			}
			is ManualBluetoothCommunicationAction.ClickScannedDevice -> {
				val device = action.scannedDevice.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = DeviceSelection.Device(device)
					return
				}
				_scope.launch {
					if (device.connectionState == BluetoothDevice.ConnectionState.Connecting) {
						lapisBt.cancelConnectionAttempt(device.address)
						return@launch
					}
					val result = if (_useSecureConnection.value) {
						lapisBt.connectToDevice(
							deviceAddress = device.address,
							serviceUuid = ConnectionUuid,
						)
					}
					else {
						lapisBt.connectToDeviceWithoutPairing(
							deviceAddress = device.address,
							serviceUuid = ConnectionUuid,
						)
					}

					when (result) {
						is LapisBt.ConnectionResult.ConnectionEstablished -> {
						}
						else -> Unit
					}
				}
			}
			is ManualBluetoothCommunicationAction.ClickConnectedDevice -> {
				val device = action.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = DeviceSelection.Device(device)
					return
				}
				_scope.launch {
					if (device.connectionState == BluetoothDevice.ConnectionState.Connecting) {
						lapisBt.cancelConnectionAttempt(device.address)
						return@launch
					}
					val result = if (_useSecureConnection.value) {
						lapisBt.connectToDevice(
							deviceAddress = device.address,
							serviceUuid = ConnectionUuid,
						)
					}
					else {
						lapisBt.connectToDeviceWithoutPairing(
							deviceAddress = device.address,
							serviceUuid = ConnectionUuid,
						)
					}

					when (result) {
						is LapisBt.ConnectionResult.ConnectionEstablished -> {
						}
						else -> Unit
					}
				}
			}
			is ManualBluetoothCommunicationAction.LongClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.LongClickScannedDevice -> {
				val device = action.scannedDevice.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(device.address)) {
							androidHelper.showToast("Could not disconnect from: ${device.name}")
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.LongClickConnectedDevice -> {
				val device = action.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(device.address)) {
							androidHelper.showToast("Could not disconnect from: ${device.name}")
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.ClickMessage -> {
				_scope.launch {
					val currentDeviceAddress = storageController.getBluetoothAddress() ?: return@launch
					if (action.message.senderAddress != currentDeviceAddress) {
						val targetDevice = lapisBt.pairedDevices.value.find { device ->
							device.address.value == action.message.senderAddress
						} ?: return@launch

						_selectedDevice.value = DeviceSelection.Device(targetDevice)
					}
				}
			}
			is ManualBluetoothCommunicationAction.EditBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = lapisBt.bluetoothDeviceName.value
			}
			is ManualBluetoothCommunicationAction.EnterBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = action.bluetoothDeviceName
			}
			is ManualBluetoothCommunicationAction.SaveBluetoothDeviceName -> {
				val newBluetoothDeviceName = _enteredBluetoothDeviceName.value ?: return

				if (lapisBt.setBluetoothDeviceName(newBluetoothDeviceName)) {
					_enteredBluetoothDeviceName.value = null
				}
				else {
					androidHelper.showToast("Couldn't change the bluetooth name")
				}
			}
			is ManualBluetoothCommunicationAction.CheckUseSecureConnection -> {
				_useSecureConnection.value = action.enabled
			}
			is ManualBluetoothCommunicationAction.SelectTargetDeviceToMessage -> {
				_selectedDevice.value = DeviceSelection.Device(action.connectedDevice)
			}
			is ManualBluetoothCommunicationAction.SelectAllDevicesToMessage -> {
				_selectedDevice.value = DeviceSelection.AllDevices
			}
			is ManualBluetoothCommunicationAction.EnableBluetooth -> {
				_scope.launch {
					requestPermissionsBeforeExecuting {
					}
				}
			}
			is ManualBluetoothCommunicationAction.PairDevice -> {
				_scope.launch {
					lapisBt.startDevicePairing(action.device.address)
				}
			}
			is ManualBluetoothCommunicationAction.UnpairDevice -> {
				_scope.launch {
					lapisBt.unpairDevice(action.device.address)
				}
			}
		}
	}

	private suspend fun sendMessage(message: String): Boolean {
		if (message.isBlank()) {
			return false
		}

		_enteredMessage.value = ""

		val currentDeviceAddress = storageController.getBluetoothAddress() ?: "unknown"
		_messages.update { current ->
			current + BluetoothMessage(
				content = message,
				senderName = null,
				senderAddress = currentDeviceAddress,
				isRead = true,
			)
		}

		val selectedDevice = _selectedDevice.value
		if (selectedDevice == DeviceSelection.None) {
			androidHelper.showToast("Please, select a connected device to mark it as target.")
			return false
		}

		val success = when (selectedDevice) {
			is DeviceSelection.AllDevices -> {
				val connectedDevices = lapisBt.pairedDevices.value.filter {
					it.connectionState == BluetoothDevice.ConnectionState.Connected
				}
				if (connectedDevices.isEmpty()) {
					androidHelper.showToast("No devices currently connected")
					false
				}
				else {
					connectedDevices.forEach { device ->
						lapisBt.sendData(device.address) { stream ->
							stream.write(message.encodeToByteArray())
						}
					}
					true
				}
			}
			is DeviceSelection.Device -> {
				lapisBt.sendData(selectedDevice.device.address) { stream ->
					stream.write(message.encodeToByteArray())
				}
			}
			else -> false
		}

		if (!success) {
			androidHelper.showToast("Message could not be sent")
		}

		return success
	}

	private suspend fun requestPermissionsBeforeExecuting(
		enableBluetooth: Boolean = true,
		action: suspend () -> Unit,
	) {
		val result = bluetoothPermissionController.request()
		if (result.allArePermanentlyDenied) {
			_permissionDialog.value = PermissionDialogState(
				title = "Permission Denied",
				message = "Please, enable bluetooth permissions in settings.",
				actionName = "Settings",
				onAction = {
					androidHelper.openAppSettings()
					_permissionDialog.value = null
				},
				onDismissRequest = {
					_permissionDialog.value = null
				},
			)
			return
		}
		if (result.values.any { !it.isGranted }) {
			androidHelper.showToast("You have to grant the permissions to perform the operation")
			return
		}

		val shouldShowEnableBluetoothDialog = enableBluetooth
			&& !lapisBt.state.value.isOn
			&& result.values.all { it == PermissionState.Granted }
		if (shouldShowEnableBluetoothDialog) {
			if (androidHelper.showEnableBluetoothDialog()) {
				action()
			}
			else {
				androidHelper.showToast("Please, enable Bluetooth to perform the operation.")
			}
			return
		}

		action()
	}

	override fun onServiceUnregistered() {
		lapisBt.dispose()
	}


	companion object {
		private val ConnectionUuid = UUID.fromString("afd70479-c800-4e92-b626-1474e450c08e")
		private const val ConnectionName = "ManualBluetoothCommunicationService"
	}
}
