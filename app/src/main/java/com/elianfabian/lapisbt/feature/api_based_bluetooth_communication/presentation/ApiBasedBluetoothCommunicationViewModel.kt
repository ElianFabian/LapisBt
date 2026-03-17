package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import android.os.Build
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.app.common.domain.MultiplePermissionController
import com.elianfabian.lapisbt.app.common.domain.PermissionController
import com.elianfabian.lapisbt.app.common.domain.PermissionState
import com.elianfabian.lapisbt.app.common.domain.StorageController
import com.elianfabian.lapisbt.app.common.domain.allArePermanentlyDenied
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data.SimpleBluetoothRpc
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data.SimpleBluetoothRpcServer
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.getOrCreateBluetoothClientApi
import com.elianfabian.lapisbt_rpc.registerBluetoothServerApi
import com.elianfabian.lapisbt_rpc.unregisterBluetoothServerApi
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

class ApiBasedBluetoothCommunicationViewModel(
	private val lapisBt: LapisBt,
	private val lapisBtRpc: LapisBtRpc,
	private val bluetoothPermissionController: MultiplePermissionController,
	private val accessFineLocationPermissionController: PermissionController,
	private val postNotificationsPermissionController: PermissionController,
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
						println("$$$ Device connected event received for device: ${event.connectedDevice}")
						if (_selectedDevice.value == ApiBasedBluetoothCommunicationState.SelectedDevice.None) {
							_selectedDevice.value = ApiBasedBluetoothCommunicationState.SelectedDevice.Device(event.connectedDevice)
						}

						val bluetoothRpcServer = SimpleBluetoothRpcServer(
							deviceAddress = event.connectedDevice.address,
							androidHelper = androidHelper,
						)

						println("$$$$$ Register bluetooth rpc server: $bluetoothRpcServer")

						lapisBtRpc.registerBluetoothServerApi<SimpleBluetoothRpc>(
							deviceAddress = event.connectedDevice.address,
							server = bluetoothRpcServer,
						)
						//lapisBtRpc.getOrCreateBluetoothClientApi<SimpleBluetoothRpc>(event.connectedDevice.address)
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						lapisBtRpc.unregisterBluetoothServerApi<SimpleBluetoothRpc>(event.disconnectedDevice.address)

						androidHelper.showToast("Device disconnected: '${event.disconnectedDevice.name}'")

						_selectedDevice.update { selection ->
							when (selection) {
								is ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices -> {
									val connectedDevices = lapisBt.pairedDevices.value.filter {
										it.connectionState == BluetoothDevice.ConnectionState.Connected
									}
									if (connectedDevices.isNotEmpty()) {
										ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices
									}
									else ApiBasedBluetoothCommunicationState.SelectedDevice.None
								}
								is ApiBasedBluetoothCommunicationState.SelectedDevice.Device -> {
									ApiBasedBluetoothCommunicationState.SelectedDevice.None
								}
								is ApiBasedBluetoothCommunicationState.SelectedDevice.None -> {
									ApiBasedBluetoothCommunicationState.SelectedDevice.None
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
	private val _permissionDialog = MutableStateFlow<ApiBasedBluetoothCommunicationState.PermissionDialogState?>(null)
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(false)
	private val _selectedDevice = MutableStateFlow<ApiBasedBluetoothCommunicationState.SelectedDevice>(ApiBasedBluetoothCommunicationState.SelectedDevice.None)

	val state = combineTuple(
		lapisBt.pairedDevices,
		lapisBt.isScanning,
		lapisBt.state,
		_permissionDialog,
		lapisBt.bluetoothDeviceName,
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
				isWaitingForConnection, enteredBluetoothDeviceName,
				useSecureConnection, selectedDevice, currentDeviceAddress, scannedDevices, connectedDevices,
			),
		->
		ApiBasedBluetoothCommunicationState(
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
			isWaitingForConnection = isWaitingForConnection,
			enteredBluetoothDeviceName = enteredBluetoothDeviceName,
			useSecureConnection = useSecureConnection,
		)
	}.stateIn(
		scope = _scope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = ApiBasedBluetoothCommunicationState(
			isBluetoothSupported = lapisBt.isBluetoothSupported,
			isBluetoothOn = lapisBt.state.value.isOn,
			useSecureConnection = _useSecureConnection.value,
			currentDeviceAddress = null,
		),
	)

	fun sendAction(action: ApiBasedBluetoothCommunicationAction) {
		println("$$$ Received action: $action")
		when (action) {
			is ApiBasedBluetoothCommunicationAction.StartScan -> {
				_scope.launch {
					if (Build.VERSION.SDK_INT < 31) {
						val result = accessFineLocationPermissionController.request()
						if (result == PermissionState.PermanentlyDenied) {
							_permissionDialog.value = ApiBasedBluetoothCommunicationState.PermissionDialogState(
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
					requestPermissionsAndEnableBluetoothBeforeExecuting {
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
			is ApiBasedBluetoothCommunicationAction.StopScan -> {
				lapisBt.stopScan()
			}
			is ApiBasedBluetoothCommunicationAction.StartServer -> {
				_scope.launch {
					requestPermissionsAndEnableBluetoothBeforeExecuting {
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
			is ApiBasedBluetoothCommunicationAction.StopServer -> {
				lapisBt.stopBluetoothServer(ConnectionUuid)
			}
			is ApiBasedBluetoothCommunicationAction.OpenBluetoothSettings -> {
				androidHelper.openBluetoothSettings()
			}
			is ApiBasedBluetoothCommunicationAction.OpenDeviceInfoSettings -> {
				androidHelper.openDeviceInfoSettings()
			}
			is ApiBasedBluetoothCommunicationAction.MakeDeviceDiscoverable -> {
				_scope.launch {
					androidHelper.showMakeDeviceDiscoverableDialog(seconds = 300)
				}
			}
			is ApiBasedBluetoothCommunicationAction.ClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = ApiBasedBluetoothCommunicationState.SelectedDevice.Device(action.device)
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
					println("$$$ Connection result1: $result")
					when (result) {
						is LapisBt.ConnectionResult.ConnectionEstablished -> {

						}
						else -> Unit
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.ClickScannedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = ApiBasedBluetoothCommunicationState.SelectedDevice.Device(action.device)
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
			is ApiBasedBluetoothCommunicationAction.LongClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.LongClickScannedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.EditBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = lapisBt.bluetoothDeviceName.value
			}
			is ApiBasedBluetoothCommunicationAction.EnterBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = action.bluetoothDeviceName
			}
			is ApiBasedBluetoothCommunicationAction.SaveBluetoothDeviceName -> {
				val newBluetoothDeviceName = _enteredBluetoothDeviceName.value ?: return

				if (lapisBt.setBluetoothDeviceName(newBluetoothDeviceName)) {
					_enteredBluetoothDeviceName.value = null
				}
				else {
					androidHelper.showToast("Couldn't change the bluetooth name")
				}
			}
			is ApiBasedBluetoothCommunicationAction.CheckUseSecureConnection -> {
				_useSecureConnection.value = action.enabled
			}
			is ApiBasedBluetoothCommunicationAction.SelectTargetDeviceToMessage -> {
				_selectedDevice.value = ApiBasedBluetoothCommunicationState.SelectedDevice.Device(action.connectedDevice)
			}
			is ApiBasedBluetoothCommunicationAction.SelectAllDevicesToMessage -> {
				_selectedDevice.value = ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices
			}
			is ApiBasedBluetoothCommunicationAction.EnableBluetooth -> {
				_scope.launch {
					requestPermissionsAndEnableBluetoothBeforeExecuting {
						// no-op, this will request the proper permissions to then enable bluetooth
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.PairDevice -> {
				_scope.launch {
					lapisBt.pairDevice(action.device.address)
				}
			}
			is ApiBasedBluetoothCommunicationAction.UnpairDevice -> {
				_scope.launch {
					lapisBt.unpairDevice(action.device.address)
				}
			}
			ApiBasedBluetoothCommunicationAction.ClickOpenAppSettingsRemotely -> {
				when (val selectedDevice = _selectedDevice.value) {
					is ApiBasedBluetoothCommunicationState.SelectedDevice.Device -> {
						val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
							deviceAddress = selectedDevice.device.address,
							apiInterface = SimpleBluetoothRpc::class,
						)

						_scope.launch {
							apiClient.openAppSettings()
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices -> {
						_scope.launch {
							val connectedDevices = lapisBt.pairedDevices.value.filter {
								it.connectionState == BluetoothDevice.ConnectionState.Connected
							}
							connectedDevices.forEach { device ->
								val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
									deviceAddress = device.address,
									apiInterface = SimpleBluetoothRpc::class,
								)

								apiClient.openAppSettings()
							}
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.None -> {
						androidHelper.showToast("Please, select a connected device to mark it as target.")
					}
				}
			}
			ApiBasedBluetoothCommunicationAction.ClickGetMyOwnAddress -> {
				when (val selectedDevice = _selectedDevice.value) {
					is ApiBasedBluetoothCommunicationState.SelectedDevice.Device -> {
						val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
							deviceAddress = selectedDevice.device.address,
							apiInterface = SimpleBluetoothRpc::class,
						)

						_scope.launch {
							val myAddress = apiClient.getMyOwnAddress()
							androidHelper.showToast("My address according to ${selectedDevice.device.address} is: $myAddress")
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices -> {
						_scope.launch {
							val connectedDevices = lapisBt.pairedDevices.value.filter {
								it.connectionState == BluetoothDevice.ConnectionState.Connected
							}
							connectedDevices.forEach { device ->
								val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
									deviceAddress = device.address,
									apiInterface = SimpleBluetoothRpc::class,
								)

								val myAddress = apiClient.getMyOwnAddress()
								androidHelper.showToast("My address according to ${device.address} is: $myAddress")
							}
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.None -> {
						androidHelper.showToast("Please, select a connected device to mark it as target.")
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.ClickShowToastRemotely -> {
				when (val selectedDevice = _selectedDevice.value) {
					is ApiBasedBluetoothCommunicationState.SelectedDevice.Device -> {
						val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
							deviceAddress = selectedDevice.device.address,
							apiInterface = SimpleBluetoothRpc::class,
						)

						_scope.launch {
							apiClient.showToast("From ${selectedDevice.device.address}: ${action.message}")
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices -> {
						val connectedDevices = lapisBt.pairedDevices.value.filter {
							it.connectionState == BluetoothDevice.ConnectionState.Connected
						}
						connectedDevices.forEach { device ->
							val apiClient = lapisBtRpc.getOrCreateBluetoothClientApi(
								deviceAddress = device.address,
								apiInterface = SimpleBluetoothRpc::class,
							)

							_scope.launch {
								apiClient.showToast("From ${device.address}: ${action.message}")
							}
						}
					}
					ApiBasedBluetoothCommunicationState.SelectedDevice.None -> {
						androidHelper.showToast("Please, select a connected device to mark it as target.")
					}
				}
			}
		}
	}

	private suspend fun requestPermissionsAndEnableBluetoothBeforeExecuting(action: suspend () -> Unit) {
		val result = bluetoothPermissionController.request()
		if (result.allArePermanentlyDenied) {
			_permissionDialog.value = ApiBasedBluetoothCommunicationState.PermissionDialogState(
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

		val shouldShowEnableBluetoothDialog = lapisBt.canEnableBluetooth
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
		private const val ConnectionName = "ApiBasedBluetoothCommunicationService"
	}
}
