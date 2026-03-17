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
import com.elianfabian.lapisbt.app.common.presentation.model.BluetoothMessage
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
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
		// In some devices (At least on Realme 6 API level 30), when you close and open the app
		// again, if you were scanning it will continue scanning, which it's kind of a weird behavior,
		// so we just manually stop it ourselves.
		lapisBt.stopScan()

//		_scope.launch {
//			lapisBt.scannedDevicesFlow.collect { scannedDevice ->
//				_scannedDevices.update { currentDevices ->
//					if (currentDevices.any { it.address == scannedDevice.address }) {
//						currentDevices
//					}
//					else {
//						currentDevices + scannedDevice
//					}
//				}
//			}
//		}

		_scope.launch {
			storageController.getBluetoothAddress()?.also { address ->
				_currentDeviceAddress.value = address
			}
		}

		_scope.launch {
			postNotificationsPermissionController.request()
		}

		_messages.update { messages ->
			messages.map { message ->
				message.copy(isRead = true)
			}
		}

		_scope.launch {
			lapisBt.state.collect { state ->
				if (state == LapisBt.BluetoothState.Off) {
					_enteredBluetoothDeviceName.value = null
				}
			}
		}
		_scope.launch {
			notificationController.events.collect { event ->
				when (event) {
					is NotificationController.NotificationEvent.OnReceiveMessageFromRemoteInput -> {
						if (sendMessage(event.message)) {
							notificationController.sendGroupNotificationMessage(
								message = NotificationController.GroupMessageNotification(
									senderName = "Me",
									content = event.message,
								)
							)
						}
						else {
							notificationController.stopLoadingGroupNotification()
						}
					}
					else -> Unit
				}
			}
		}
		_scope.launch {
			lapisBt.events.collect { event ->
				println("$$$ event: $event")
				when (event) {
					is LapisBt.Event.OnDeviceConnected -> {
						if (_selectedDevice.value == ManualBluetoothCommunicationState.SelectedDevice.None) {
							_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.Device(event.connectedDevice)
						}

						if (storageController.getBluetoothAddress() == null) {
							launch {
								lapisBt.sendData(event.connectedDevice.address) { stream ->
									val dataStream = DataOutputStream(stream)

									dataStream.writeUTF("get-address")
								}
							}
						}

						launch {
							lapisBt.receiveData(event.connectedDevice.address) { stream ->
								val dataStream = DataInputStream(stream)
								while (true) {
									val type = dataStream.readUTF()
									when (type) {
										"get-address" -> {
											lapisBt.sendData(event.connectedDevice.address) { stream ->
												val dataStream = DataOutputStream(stream)

												dataStream.writeUTF("address")
												dataStream.writeUTF(event.connectedDevice.address)
											}
										}
										"address" -> {
											val address = dataStream.readUTF()
											_currentDeviceAddress.value = address
											storageController.setBluetoothAddress(address)
										}
										"message" -> {
											val messageContent = dataStream.readUTF()

											val message = BluetoothMessage(
												content = messageContent,
												isRead = false,
												senderName = event.connectedDevice.name,
												senderAddress = event.connectedDevice.address,
											)

											val messages = _messages.updateAndGet {
												it + message
											}

											val currentDeviceAddress = storageController.getBluetoothAddress() ?: error("No device address set")

											if (androidHelper.isAppInBackground() || androidHelper.isAppClosed()) {
												notificationController.sendGroupNotificationMessage(
													message = messages
														.last { it.senderAddress != currentDeviceAddress && !it.isRead }
														.let {
															NotificationController.GroupMessageNotification(
																senderName = it.senderName ?: it.senderAddress,
																content = it.content,
															)
														}
												)
											}
										}
									}
								}
							}
						}

						androidHelper.showToast("Device connected: '${event.connectedDevice.name}'")
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						androidHelper.showToast("Device disconnected: '${event.disconnectedDevice.name}'")

						_selectedDevice.update { selection ->
							when (selection) {
								is ManualBluetoothCommunicationState.SelectedDevice.AllDevices -> {
									val connectedDevices = lapisBt.pairedDevices.value.filter {
										it.connectionState == BluetoothDevice.ConnectionState.Connected
									}
									if (connectedDevices.isNotEmpty()) {
										ManualBluetoothCommunicationState.SelectedDevice.AllDevices
									}
									else ManualBluetoothCommunicationState.SelectedDevice.None
								}
								is ManualBluetoothCommunicationState.SelectedDevice.Device -> {
									ManualBluetoothCommunicationState.SelectedDevice.None
								}
								is ManualBluetoothCommunicationState.SelectedDevice.None -> {
									ManualBluetoothCommunicationState.SelectedDevice.None
								}
							}
						}
					}
					is LapisBt.Event.OnDeviceScanned -> {
						// no-op
					}
					is LapisBt.Event.OnPairingRequest -> {
						// no-op
					}
					is LapisBt.Event.OnPairingFailed -> {
						// no-op
					}
				}
			}
		}
	}

	private val _currentDeviceAddress = MutableStateFlow<String?>(null)
	private val _permissionDialog = MutableStateFlow<ManualBluetoothCommunicationState.PermissionDialogState?>(null)
	private val _messages = MutableStateFlow<List<BluetoothMessage>>(emptyList())
	private val _enteredMessage = MutableStateFlow("")
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(false)
	private val _selectedDevice = MutableStateFlow<ManualBluetoothCommunicationState.SelectedDevice>(ManualBluetoothCommunicationState.SelectedDevice.None)

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
	).map {
			(
				devices, isScanning, bluetoothState, permissionDialog, bluetoothName,
				messages, enteredMessage, isWaitingForConnection, enteredBluetoothDeviceName,
				useSecureConnection, selectedDevice, currentDeviceAddress, scannedDevices, connectedDevices,
			),
		->
		ManualBluetoothCommunicationState(
			pairedDevices = devices,
//				.filter {
//				it.pairingState == BluetoothDevice.PairingState.Paired
//			}.sortedByDescending {
//				it.connectionState == BluetoothDevice.ConnectionState.Connected
//			}
			scannedDevices = scannedDevices,
//				.filter {
//				it.pairingState != BluetoothDevice.PairingState.Paired
//			}.sortedByDescending {
//				it.connectionState == BluetoothDevice.ConnectionState.Connected
//			}
			selectedDevice = selectedDevice,
			connectedDevices = connectedDevices,
			currentDeviceAddress = currentDeviceAddress,
			isScanning = isScanning,
			isBluetoothSupported = lapisBt.isBluetoothSupported,
			isBluetoothOn = bluetoothState.isOn,
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothName,
			messages = messages,
			enteredMessage = enteredMessage,
			isWaitingForConnection = isWaitingForConnection,
			enteredBluetoothDeviceName = enteredBluetoothDeviceName,
			useSecureConnection = useSecureConnection,
		)
	}.stateIn(
		scope = _scope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = ManualBluetoothCommunicationState(
			isBluetoothSupported = lapisBt.isBluetoothSupported,
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
							_permissionDialog.value = ManualBluetoothCommunicationState.PermissionDialogState(
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
							// In some devices, at least for Xiaomi Mi MIX 2S API level 29 (for Samsung Galaxy Note 9 API level 29 this does not reproduce),
							// if this returns false we likely need to turn on location
							// Maybe in some cases it is not the case, we'll have to see
							// The ideal solution would be to know in which concrete cases this is needed
							// I think it is a combination of manufacturer and API level
							if (androidHelper.showEnableLocationDialog()) {
								//_scannedDevices.value = emptyList()
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
					_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.Device(action.device)
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
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.Device(action.device)
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
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is ManualBluetoothCommunicationAction.ClickMessage -> {
				_scope.launch {
					val currentDeviceAddress = storageController.getBluetoothAddress() ?: return@launch
					if (action.message.senderAddress != currentDeviceAddress) {
						val targetDevice = lapisBt.pairedDevices.value.find { device ->
							device.address == action.message.senderAddress
						} ?: return@launch

						_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.Device(targetDevice)
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
				_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.Device(action.connectedDevice)
			}
			is ManualBluetoothCommunicationAction.SelectAllDevicesToMessage -> {
				_selectedDevice.value = ManualBluetoothCommunicationState.SelectedDevice.AllDevices
			}
			is ManualBluetoothCommunicationAction.EnableBluetooth -> {
				_scope.launch {
					requestPermissionsBeforeExecuting {
						// no-op, this will request the proper permissions to then enable bluetooth
					}
				}
			}
			is ManualBluetoothCommunicationAction.PairDevice -> {
				_scope.launch {
					lapisBt.pairDevice(action.device.address)
				}
			}
			is ManualBluetoothCommunicationAction.UnpairDevice -> {
				_scope.launch {
					lapisBt.unpairDevice(action.device.address)
				}
			}
		}
	}

	private suspend fun sendMessage(
		messageContent: String,
	): Boolean {
		val selectedDevice = _selectedDevice.value
		if (selectedDevice == ManualBluetoothCommunicationState.SelectedDevice.None) {
			androidHelper.showToast("Please, select a connected device to mark it as target.")
			return false
		}
		if (messageContent.isBlank()) {
			androidHelper.showToast("Please, enter a message to send.")
			return false
		}
		val connectedDevices = state.value.connectedDevices.filter {
			it.connectionState == BluetoothDevice.ConnectionState.Connected
		}
		if (connectedDevices.isEmpty()) {
			androidHelper.showToast("Please, connect to a device before sending a message.")
			return false
		}
		if (selectedDevice is ManualBluetoothCommunicationState.SelectedDevice.Device) {
			if (connectedDevices.none { it.address == selectedDevice.device.address }) {
				androidHelper.showToast("Please, connect to the device with address: ${selectedDevice.device.address} before sending a message.")
				return false
			}
		}

		return coroutineScope {
			when (selectedDevice) {
				is ManualBluetoothCommunicationState.SelectedDevice.Device -> {

					lapisBt.sendData(selectedDevice.device.address) { stream ->
						val dataStream = DataOutputStream(stream)

						dataStream.writeUTF("message")
						dataStream.writeUTF(messageContent)
					}

					val currentDeviceAddress = storageController.getBluetoothAddress() ?: return@coroutineScope false

					val message = BluetoothMessage(
						content = messageContent,
						senderAddress = currentDeviceAddress,
						isRead = true,
						senderName = lapisBt.bluetoothDeviceName.value,
					)

					_messages.update {
						it + message
					}
					_enteredMessage.value = ""

					return@coroutineScope true
				}
				is ManualBluetoothCommunicationState.SelectedDevice.AllDevices -> {
					val messages = lapisBt.pairedDevices.value.filter {
						it.connectionState == BluetoothDevice.ConnectionState.Connected
					}.map { connectedDevice ->
						async {
							lapisBt.sendData(connectedDevice.address) { stream ->
								val dataStream = DataOutputStream(stream)

								dataStream.writeUTF("message")
								dataStream.writeUTF(messageContent)
							}

							val currentDeviceAddress = storageController.getBluetoothAddress() ?: error("Bluetooth address is null")

							val message = BluetoothMessage(
								content = messageContent,
								senderAddress = currentDeviceAddress,
								isRead = true,
								senderName = lapisBt.bluetoothDeviceName.value,
							)

							message
						}
					}.awaitAll()

					if (messages.isNotEmpty()) {
						_messages.update {
							it + messages
						}

						_enteredMessage.value = ""

						return@coroutineScope true
					}

					return@coroutineScope false
				}
				is ManualBluetoothCommunicationState.SelectedDevice.None -> {
					return@coroutineScope true
				}
			}
		}
	}

	private suspend fun requestPermissionsBeforeExecuting(
		enableBluetooth: Boolean = true,
		action: suspend () -> Unit,
	) {
		val result = bluetoothPermissionController.request()
		if (result.allArePermanentlyDenied) {
			_permissionDialog.value = ManualBluetoothCommunicationState.PermissionDialogState(
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

		// Even though there are different permissions for different things
		// since we always request them all at once we can just check for
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
		private const val ConnectionName = "ManualBluetoothChat"
	}
}
