package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import android.os.Build
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.app.common.domain.MultiplePermissionController
import com.elianfabian.lapisbt.app.common.domain.PermissionController
import com.elianfabian.lapisbt.app.common.domain.PermissionState
import com.elianfabian.lapisbt.app.common.domain.StorageController
import com.elianfabian.lapisbt.app.common.domain.allArePermanentlyDenied
import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelection
import com.elianfabian.lapisbt.app.common.presentation.component.PermissionDialogState
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data.SimpleBluetoothRpc
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.data.SimpleBluetoothRpcServer
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.registerBluetoothServerService
import com.elianfabian.lapisbt_rpc.unregisterBluetoothServerService
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
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
						if (_deviceSelection.value == DeviceSelection.None) {
							_deviceSelection.value = DeviceSelection.Device(event.device)
						}

						val bluetoothRpcServer = SimpleBluetoothRpcServer(
							androidHelper = androidHelper,
						)

						lapisBtRpc.registerBluetoothServerService<SimpleBluetoothRpc>(
							deviceAddress = event.device.address,
							server = bluetoothRpcServer,
						)
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						lapisBtRpc.unregisterBluetoothServerService<SimpleBluetoothRpc>(event.device.address)

						androidHelper.showToast("Device disconnected: '${event.device.name}'")

						_deviceSelection.update { selection ->
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
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(false)
	private val _deviceSelection = MutableStateFlow<DeviceSelection>(DeviceSelection.None)
	private val _rpcTestState = MutableStateFlow(ApiBasedBluetoothCommunicationState.RpcTestState())

	private val _activeFlowJobs = mutableMapOf<String, Job>()

	val state = combineTuple(
		lapisBt.pairedDevices,
		lapisBt.isScanning,
		lapisBt.state,
		_permissionDialog,
		lapisBt.bluetoothDeviceName,
		lapisBt.activeBluetoothServersUuids.map { it.isNotEmpty() },
		_enteredBluetoothDeviceName,
		_useSecureConnection,
		_deviceSelection,
		_currentDeviceAddress,
		lapisBt.scannedDevices,
		lapisBt.connectedDevices,
		_rpcTestState,
	).map { (
		pairedDevices,
		isScanning,
		bluetoothState,
		permissionDialog,
		bluetoothDeviceName,
		isWaitingForConnection,
		enteredBluetoothDeviceName,
		useSecureConnection,
		selectedDevice,
		currentDeviceAddress,
		scannedDevices,
		connectedDevices,
		rpcTestState,
	) ->
		ApiBasedBluetoothCommunicationState(
			pairedDevices = pairedDevices,
			isScanning = isScanning,
			isBluetoothOn = bluetoothState.isOn,
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothDeviceName,
			isWaitingForConnection = isWaitingForConnection,
			enteredBluetoothDeviceName = enteredBluetoothDeviceName,
			useSecureConnection = useSecureConnection,
			deviceSelection = selectedDevice,
			currentDeviceAddress = currentDeviceAddress,
			scannedDevices = scannedDevices,
			connectedDevices = connectedDevices,
			rpcTestState = rpcTestState,
			isBluetoothSupported = lapisBt.isBluetoothClassicSupported,
		)
	}.stateIn(
		scope = _scope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = ApiBasedBluetoothCommunicationState(
			isBluetoothSupported = lapisBt.isBluetoothClassicSupported,
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
			is ApiBasedBluetoothCommunicationAction.StopScan -> {
				lapisBt.stopScan()
			}
			is ApiBasedBluetoothCommunicationAction.StartServer -> {
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
					requestPermissionsBeforeExecuting(enableBluetooth = false) {
						androidHelper.showMakeDeviceDiscoverableDialog(seconds = 300)
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.ClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_deviceSelection.value = DeviceSelection.Device(action.device)
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
				val device = action.scannedDevice.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_deviceSelection.value = DeviceSelection.Device(device)
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
				val device = action.scannedDevice.device
				if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_scope.launch {
						if (!lapisBt.disconnectFromDevice(device.address)) {
							androidHelper.showToast("Could not disconnect from: ${device.name}")
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
				_deviceSelection.value = DeviceSelection.Device(action.connectedDevice)
			}
			is ApiBasedBluetoothCommunicationAction.SelectAllDevicesToMessage -> {
				_deviceSelection.value = DeviceSelection.AllDevices
			}
			is ApiBasedBluetoothCommunicationAction.EnableBluetooth -> {
				_scope.launch {
					requestPermissionsBeforeExecuting {
					}
				}
			}
			is ApiBasedBluetoothCommunicationAction.PairDevice -> {
				_scope.launch {
					lapisBt.startDevicePairing(action.device.address)
				}
			}
			is ApiBasedBluetoothCommunicationAction.UnpairDevice -> {
				_scope.launch {
					lapisBt.unpairDevice(action.device.address)
				}
			}
			is ApiBasedBluetoothCommunicationAction.ClickShowToastRemotely -> {
				performRpcAction { apiClient ->
					apiClient.showToast(action.message)
					addLog("Sent toast: ${action.message}")
				}
			}
			ApiBasedBluetoothCommunicationAction.ClickGetMyOwnAddress -> {
				performRpcAction { apiClient ->
					val address = apiClient.getMyOwnAddress()
					androidHelper.showToast("My address: $address")
					addLog("My address: $address")
				}
			}
			ApiBasedBluetoothCommunicationAction.StartRemoteVibration -> {
				println("$$$ VM Start Vibrate")
				performRpcAction { apiClient ->
					apiClient.startVibration()
					addLog("Started remote vibration")
				}
			}
			ApiBasedBluetoothCommunicationAction.StopRemoteVibration -> {
				println("$$$ VM Stop Vibrate")
				performRpcAction { apiClient ->
					apiClient.stopVibration()
					addLog("Stopped remote vibration")
				}
			}
			is ApiBasedBluetoothCommunicationAction.ToggleFlashlight -> {
				performRpcAction { apiClient ->
					apiClient.setFlashlight(action.enabled)
					_rpcTestState.update { it.copy(flashlightEnabled = action.enabled) }
					addLog("Set flashlight: ${action.enabled}")
				}
			}
			is ApiBasedBluetoothCommunicationAction.StartLightSensor -> {
				startFlowRpc("lightSensor") { apiClient ->
					apiClient.lightSensor()
				}
			}
			is ApiBasedBluetoothCommunicationAction.StopLightSensor -> {
				stopFlowRpc("lightSensor")
			}
			is ApiBasedBluetoothCommunicationAction.StartRandomNumbers -> {
				startFlowRpc("randomNumbers") { apiClient ->
					apiClient.randomNumbers(action.intervalMillis)
				}
			}
			is ApiBasedBluetoothCommunicationAction.StopRandomNumbers -> {
				stopFlowRpc("randomNumbers")
			}
			is ApiBasedBluetoothCommunicationAction.StartProcessDataStream -> {
				startFlowRpc("processDataStream") { apiClient ->
					val inputFlow = flow {
						var i = 1
						while (true) {
							emit(i++)
							delay(1000)
						}
					}
					apiClient.processDataStream(inputFlow)
				}
			}
			is ApiBasedBluetoothCommunicationAction.StopProcessDataStream -> {
				stopFlowRpc("processDataStream")
			}
			is ApiBasedBluetoothCommunicationAction.ClickClearLogs -> {
				_rpcTestState.update { it.copy(logs = emptyList(), latestValues = emptyMap()) }
			}
		}
	}

	private fun performRpcAction(block: suspend (SimpleBluetoothRpc) -> Unit) {
		val selectedDevice = _deviceSelection.value
		if (selectedDevice is DeviceSelection.Device) {
			val apiClient = lapisBtRpc.getOrCreateBluetoothClientService(
				deviceAddress = selectedDevice.device.address,
				serviceInterface = SimpleBluetoothRpc::class,
			)
			_scope.launch {
				try {
					block(apiClient)
				} catch (e: Exception) {
					addLog("Error: ${e.message}")
					e.printStackTrace()
				}
			}
		} else {
			androidHelper.showToast("Please, select a connected device.")
		}
	}

	private fun startFlowRpc(key: String, block: (SimpleBluetoothRpc) -> Flow<Any>) {
		val selectedDevice = _deviceSelection.value
		if (selectedDevice is DeviceSelection.Device) {
			if (_activeFlowJobs.containsKey(key)) return

			val apiClient = lapisBtRpc.getOrCreateBluetoothClientService(
				deviceAddress = selectedDevice.device.address,
				serviceInterface = SimpleBluetoothRpc::class,
			)

			val job = _scope.launch {
				try {
					_rpcTestState.update { it.copy(activeFlows = it.activeFlows + key) }
					addLog("Started flow: $key")
					block(apiClient).collect { value ->
						_rpcTestState.update { it.copy(latestValues = it.latestValues + (key to value.toString())) }
					}
				}
				catch (_: CancellationException) {
					addLog("Flow cancelled: $key")
				}
				catch (e: Exception) {
					addLog("Flow Error ($key): ${e.message}")
					e.printStackTrace()
				} finally {
					_rpcTestState.update { it.copy(activeFlows = it.activeFlows - key) }
					_activeFlowJobs.remove(key)
					addLog("Stopped flow: $key")
				}
			}
			_activeFlowJobs[key] = job
		} else {
			androidHelper.showToast("Please, select a connected device.")
		}
	}

	private fun stopFlowRpc(key: String) {
		_activeFlowJobs[key]?.cancel()
	}

	private fun addLog(message: String) {
		_rpcTestState.update { it.copy(logs = it.logs + message) }
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
		lapisBtRpc.dispose()
	}


	companion object {
		private val ConnectionUuid = UUID.fromString("afd70479-c800-4e92-b626-1474e450c08e")
		private const val ConnectionName = "ApiBasedBluetoothCommunicationService"
	}
}
