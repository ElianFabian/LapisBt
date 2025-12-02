package com.elianfabian.lapisbt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
import android.bluetooth.BluetoothClass.Device.Major.COMPUTER
import android.bluetooth.BluetoothClass.Device.Major.HEALTH
import android.bluetooth.BluetoothClass.Device.Major.IMAGING
import android.bluetooth.BluetoothClass.Device.Major.MISC
import android.bluetooth.BluetoothClass.Device.Major.NETWORKING
import android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
import android.bluetooth.BluetoothClass.Device.Major.PHONE
import android.bluetooth.BluetoothClass.Device.Major.TOY
import android.bluetooth.BluetoothClass.Device.Major.WEARABLE
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.elianfabian.lapisbt.broadcast_receiver.BluetoothDeviceConnectionBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.BluetoothDeviceNameChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.BluetoothDiscoveryStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.BluetoothStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceAliasChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceBondStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceFoundBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceUuidsChangeBroadcastReceiver
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class LapisBtImpl(
	private val context: Context,
) : LapisBt {

	private val _bluetoothAdapter by lazy {
		val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: throw IllegalStateException("Couldn't get the BluetoothManager")
		bluetoothManager.adapter
	}

	private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	private val _isBluetoothConnectPermissionGranted = MutableStateFlow(
		if (Build.VERSION.SDK_INT >= 31) {
			ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		}
		else true
	)


	// Represents the state of paired and/or connected devices
	private val _devices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val devices = _devices.asStateFlow()

	private val _scannedDevices = MutableSharedFlow<BluetoothDevice>()
	override val scannedDevices = _scannedDevices.asSharedFlow()

	private val _events = MutableSharedFlow<LapisBt.Event>()
	override val events = _events.asSharedFlow()

	private val _bluetoothDeviceName = MutableStateFlow<String?>(
		if (canEnableBluetooth) {
			_bluetoothAdapter?.name
		}
		else null
	)
	override val bluetoothDeviceName = _bluetoothDeviceName.asStateFlow()

	override val isBluetoothSupported: Boolean
		get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

	override val canEnableBluetooth: Boolean
		@SuppressLint("InlinedApi")
		get() = if (Build.VERSION.SDK_INT >= 31) {
			context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		}
		else true

	private val _bluetoothState = MutableStateFlow(
		if (_bluetoothAdapter?.isEnabled == true) {
			LapisBt.BluetoothState.On
		}
		else LapisBt.BluetoothState.Off
	)
	override val state = _bluetoothState.asStateFlow()

	private val _isScanning = MutableStateFlow(
		if (isScanningPermissionGranted()) {
			_bluetoothAdapter?.isDiscovering == true
		}
		else false
	)
	override val isScanning = _isScanning.asStateFlow()

	private val _activeBluetoothServersUuids = MutableStateFlow(emptyList<UUID>())
	override val activeBluetoothServers = _activeBluetoothServersUuids.asStateFlow()

	private var _bluetoothServerSocketByServiceUuid: MutableMap<UUID, BluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<String, BluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<String, Job> = ConcurrentHashMap()

	private val _activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {

		override fun onActivityResumed(activity: Activity) {
			updateDevices()

			_isBluetoothConnectPermissionGranted.value = Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

			if (canEnableBluetooth) {
				_bluetoothDeviceName.value = _bluetoothAdapter.name
			}
		}

		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
		override fun onActivityPaused(a: Activity) {}
		override fun onActivityStarted(a: Activity) {}
		override fun onActivityStopped(a: Activity) {}
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
		override fun onActivityDestroyed(a: Activity) {}
	}

	private val _deviceFoundReceiver = DeviceFoundBroadcastReceiver(
		onDeviceFound = { androidDeviceFound ->
			val newDevice = androidDeviceFound.toModel(
				connectionState = BluetoothDevice.ConnectionState.Disconnected,
			)

			_scope.launch {
				_scannedDevices.emit(newDevice)
			}
		}
	)

	private val _discoveryStateChangeReceiver = BluetoothDiscoveryStateChangeBroadcastReceiver(
		onDiscoveryStateChange = { isDiscovering ->
			_isScanning.value = isDiscovering
		}
	)

	private val _bluetoothStateChangeReceiver = BluetoothStateChangeBroadcastReceiver(
		onStateChange = { state ->
			when (state) {
				BluetoothAdapter.STATE_ON -> {
					_bluetoothState.value = LapisBt.BluetoothState.On
				}
				BluetoothAdapter.STATE_TURNING_ON -> {
					_bluetoothState.value = LapisBt.BluetoothState.TurningOn
				}
				BluetoothAdapter.STATE_OFF -> {
					_bluetoothState.value = LapisBt.BluetoothState.Off
				}
				BluetoothAdapter.STATE_TURNING_OFF -> {
					_bluetoothState.value = LapisBt.BluetoothState.TurningOff
				}
			}
		}
	)

	private val _bluetoothDeviceConnectionReceiver = BluetoothDeviceConnectionBroadcastReceiver(
		onConnectionStateChange = { androidDevice, isConnected ->
			// When we try to connect to a paired device, this callback executes with isConnected to true and after some small time (around 4s)
			// it executes again with isConnected to false

			_scope.launch {
				println("$$$$$ BluetoothDeviceConnectionBroadcastReceiver androidDevice: $androidDevice, isConnected: $isConnected")
				if (!isConnected) {
					val clientSocket = _clientSocketByAddress.remove(androidDevice.address)
					val wasConnected = clientSocket?.isConnected == true
					println("$$$$$ clientSocket.isConnected: ${clientSocket?.isConnected}")

					clientSocket?.close()
					_clientJobByAddress[androidDevice.address]?.cancel()
					_clientJobByAddress.remove(androidDevice.address)
					//_clientSharedFlowByAddress.remove(androidDevice.address)

					if (wasConnected) {
						_events.emit(
							LapisBt.Event.OnDeviceDisconnected(
								disconnectedDevice = _devices.value.find { it.address == androidDevice.address } ?: run {
									println("$$$ not found: $androidDevice")
									return@launch
								},
								manuallyDisconnected = false,
							)
						)
					}

					_devices.update { devices ->
						devices.map { device ->
							if (device.address == androidDevice.address) {
								device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
							}
							else device
						}
					}
				}
			}
		}
	)

	private val _bondStateChangeReceiver = DeviceBondStateChangeBroadcastReceiver(
		onStateChange = { androidDevice, state ->
			_devices.update { devices ->
				devices.map { existingDevice ->
					if (existingDevice.address == androidDevice.address) {
						existingDevice.copy(
							// I don't remember why I set the name here, so we'll comment out this for now
							//name = androidDevice.name ?: existingDevice.name,
							pairingState = when (state) {
								BOND_BONDED -> BluetoothDevice.PairingState.Paired
								BOND_BONDING -> BluetoothDevice.PairingState.Pairing
								BOND_NONE -> BluetoothDevice.PairingState.None
								else -> BluetoothDevice.PairingState.None
							},
						)
					}
					else existingDevice
				}
			}
		}
	)

	private val _bluetoothDeviceNameChangeReceiver = BluetoothDeviceNameChangeBroadcastReceiver(
		onNameChange = { newName ->
			_bluetoothDeviceName.value = newName
		}
	)

	private val _deviceUuidsChangeReceiver = DeviceUuidsChangeBroadcastReceiver(
		onUuidsChange = { androidDevice, uuids ->
			_devices.update { devices ->
				devices.map { device ->
					if (device.address == androidDevice.address) {
						device.copy(uuids = uuids)
					}
					else device
				}
			}
		}
	)

	private val _deviceAliasChangeReceiver = DeviceAliasChangeBroadcastReceiver(
		onAliasChanged = { androidDevice, newAlias ->
			_devices.update { devices ->
				devices.map { device ->
					if (device.address == androidDevice.address) {
						device.copy(alias = newAlias)
					}
					else device
				}
			}
		}
	)


	init {
		initialize()
	}


	override fun setBluetoothDeviceName(newName: String): Boolean {
		if (!canEnableBluetooth) {
			return false
		}
//		if (!canChangeBluetoothDeviceName) {
//			return false
//		}
		val adapter = _bluetoothAdapter ?: return false

		// Not all devices support changing the Bluetooth name, and there doesn't seem to be a way to check it
		// Here's a list of devices that I tested that support it:
		// - Google
		// - Motorola
		// - Sony Xperia 10 (34)
		// - SHARP AQUOS
		// - ZTE
		// - FUJITSU
		//
		// And here's a list of devices that I tested that don't support it:
		// - Huawei
		// - Xiaomi
		// - Realme
		// - Samsung
		//
		// Notes:
		// - Bluetooth must be enabled to change the name
		// - Immediately calling BluetoothAdapter.getName() after calling BluetoothAdapter.setName(...) won't return the new name
		return adapter.setName(newName)
	}

	// On some devices like Xiaomi Mi MIX 2S - API 29
	// This won't work unless the location is enabled
	override fun startScan(): Boolean {
		if (!isScanningPermissionGranted()) {
			return false
		}

		val adapter = _bluetoothAdapter ?: return false

		updateDevices()

		return adapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
			return false
		}

		val adapter = _bluetoothAdapter ?: return false

		return adapter.cancelDiscovery()
	}

	override suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override fun stopBluetoothServer(serviceUuid: UUID) {
		val serverSocket = _bluetoothServerSocketByServiceUuid[serviceUuid] ?: return // Maybe we should throw an exception if there are no serverSockets with such UUID?
		serverSocket.close()

		_bluetoothServerSocketByServiceUuid.remove(serviceUuid)
	}

	override suspend fun connectToDevice(deviceAddress: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun connectToDeviceWithoutPairing(deviceAddress: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override suspend fun disconnectFromDevice(deviceAddress: String): Boolean {
		requireValidAddress(deviceAddress)

		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return false
		}

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnecting)
				}
				else device
			}
		}

		// If the clientSocket is null it should mean it was already disconnected
		val clientSocket = _clientSocketByAddress[deviceAddress] ?: return false
		if (!clientSocket.isConnected) {
			return false
		}

		val manuallyDisconnected = try {
			clientSocket.outputStream.write(byteArrayOf())
			true
		}
		catch (e: IOException) {
			if (e.message.orEmpty().contains("Broken pipe")) {
				false
			}
			else throw e
		}

		println("$$$$$ disconnectFromDevice isConnected: ${clientSocket.isConnected}")
		try {
			clientSocket.close()
			_clientSocketByAddress.remove(deviceAddress)
			_clientJobByAddress[deviceAddress]?.cancel()
			_clientJobByAddress.remove(deviceAddress)

			_events.emit(
				LapisBt.Event.OnDeviceDisconnected(
					disconnectedDevice = _devices.value.first { it.address == deviceAddress },
					manuallyDisconnected = manuallyDisconnected,
				)
			)

			_devices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			updateDevices()
			println("$$$$$ disconnectFromDevice effectively disconnected: $clientSocket")
			return true
		}
		catch (e: IOException) {
			println("$$$$$ disconnectFromDevice() error closing socket: ${e.message}")
		}

		return false
	}

	override suspend fun cancelConnectionAttempt(deviceAddress: String): Boolean {
		requireValidAddress(deviceAddress)

		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return false
		}

		val clientSocket = _clientSocketByAddress[deviceAddress] ?: return false

		if (clientSocket.isConnected) {
			return false
		}

		try {
			clientSocket.close()
			_clientSocketByAddress.remove(deviceAddress)
			_clientJobByAddress[deviceAddress]?.cancel()
			_clientJobByAddress.remove(deviceAddress)

			_devices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
		}
		catch (e: IOException) {
			println("$$$ cancelConnectionAttempt error: $e")
			return false
		}

		return true
	}

	// We haven't tested this yet
	override fun unpairDevice(deviceAddress: String): Boolean {
		val adapter = _bluetoothAdapter ?: return false

		try {
			val androidDevice = adapter.getRemoteDevice(deviceAddress)
			val removeBondMethod = androidDevice.javaClass.getMethod("removeBond")

			return removeBondMethod.invoke(androidDevice) as Boolean
		}
		catch (e: Exception) {
			e.printStackTrace()
			return false
		}
	}

	override suspend fun sendData(deviceAddress: String, action: suspend (stream: OutputStream) -> Unit): Boolean {
		requireValidAddress(deviceAddress)

		val clientSocket = _clientSocketByAddress[deviceAddress]

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return withContext(Dispatchers.IO) {
			ensureActive()

			try {
				action(clientSocket.outputStream)
			}
			catch (_: IOException) {
				return@withContext false
			}

			return@withContext true
		}
	}

	override suspend fun receiveData(deviceAddress: String, action: suspend (stream: InputStream) -> Unit): Boolean {
		requireValidAddress(deviceAddress)

		val clientSocket = _clientSocketByAddress[deviceAddress]

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return withContext(Dispatchers.IO) {
			ensureActive()

			try {
				action(clientSocket.inputStream)
			}
			catch (_: IOException) {
				return@withContext false
			}

			return@withContext true
		}
	}


	override fun dispose() {
		val application = context.applicationContext as Application
		application.unregisterActivityLifecycleCallbacks(_activityLifecycleCallbacks)

		_scope.cancel()

		_bluetoothServerSocketByServiceUuid.forEach { (_, serverSocket) ->
			serverSocket.close()
		}
		_bluetoothServerSocketByServiceUuid.clear()

		_clientSocketByAddress.forEach { (_, clientSocket) ->
			clientSocket.close()
		}
		_clientSocketByAddress.clear()

		_clientJobByAddress.clear()

		context.unregisterReceiver(_bluetoothDeviceNameChangeReceiver)
		context.unregisterReceiver(_deviceFoundReceiver)
		context.unregisterReceiver(_discoveryStateChangeReceiver)
		context.unregisterReceiver(_bluetoothStateChangeReceiver)
		context.unregisterReceiver(_bluetoothDeviceConnectionReceiver)
		context.unregisterReceiver(_bondStateChangeReceiver)
		context.unregisterReceiver(_deviceUuidsChangeReceiver)
		context.unregisterReceiver(_deviceAliasChangeReceiver)
	}


	private fun initialize() {
		val application = context.applicationContext as Application
		application.registerActivityLifecycleCallbacks(_activityLifecycleCallbacks)

		_scope.launch {
			_bluetoothState.collect { state ->
				if (state == LapisBt.BluetoothState.Off) {
					_bluetoothServerSocketByServiceUuid.forEach { (_, serverSocket) ->
						serverSocket.close()
					}
					_bluetoothServerSocketByServiceUuid.clear()

					_clientSocketByAddress.forEach { (_, socket) ->
						socket.close()
					}
					_clientSocketByAddress.clear()

					_devices.update { devices ->
						devices.map { device ->
							val disconnectedDevice = device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)

							if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
								launch {
									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											disconnectedDevice = disconnectedDevice,
											manuallyDisconnected = true,
										)
									)
								}
							}

							disconnectedDevice
						}
					}
				}
			}
		}
		_scope.launch {
			combine(
				_bluetoothState.map { it.isOn },
				_isBluetoothConnectPermissionGranted,
			) { isBluetoothOn, isBluetoothConnectPermissionGranted ->
				if (isBluetoothOn) {
					updateDevices()
				}
				if (isBluetoothConnectPermissionGranted) {
					_bluetoothDeviceName.value = _bluetoothAdapter?.name
				}
			}.collect()
		}
		context.registerReceiver(
			_bluetoothDeviceNameChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED),
		)
		context.registerReceiver(
			_deviceFoundReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_FOUND),
		)
		context.registerReceiver(
			_discoveryStateChangeReceiver,
			IntentFilter().apply {
				addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
				addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
			},
		)
		context.registerReceiver(
			_bluetoothStateChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
		)
		context.registerReceiver(
			_bluetoothDeviceConnectionReceiver,
			IntentFilter().apply {
				addAction(AndroidBluetoothDevice.ACTION_ACL_CONNECTED)
				addAction(AndroidBluetoothDevice.ACTION_ACL_DISCONNECTED)
			},
		)
		context.registerReceiver(
			_bondStateChangeReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED),
		)
		context.registerReceiver(
			_deviceUuidsChangeReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_UUID),
		)

		if (Build.VERSION.SDK_INT >= 35) {
			// TODO: we should test that this works
			context.registerReceiver(
				_deviceAliasChangeReceiver,
				IntentFilter(AndroidBluetoothDevice.ACTION_ALIAS_CHANGED),
			)
		}
	}

	private fun isScanningPermissionGranted(): Boolean {
		return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
	}

	private fun updateDevices() {
		if (!canEnableBluetooth) {
			return
		}
		val adapter = _bluetoothAdapter ?: return

		_devices.updateAndGet {
			val connectedDevices = _clientSocketByAddress.keys.map { address ->
				adapter.getRemoteDevice(address)
			}.map { androidDevice ->
				androidDevice.toModel(
					connectionState = BluetoothDevice.ConnectionState.Connected,
				)
			}

			val pairedDevices = adapter.bondedDevices.mapNotNull { androidDevice ->
				if (_clientSocketByAddress.contains(androidDevice.address)) {
					return@mapNotNull null
				}

				androidDevice.toModel(
					connectionState = BluetoothDevice.ConnectionState.Disconnected,
				)
			}

			val newList = connectedDevices + pairedDevices

			newList.sortedWith(
				compareByDescending<BluetoothDevice> { it.connectionState == BluetoothDevice.ConnectionState.Connected }
					.thenByDescending { it.pairingState == BluetoothDevice.PairingState.Paired }
					.thenBy { it.address }
			)
		}
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	private suspend fun startBluetoothServerInternal(
		serviceName: String,
		serviceUuid: UUID,
		insecure: Boolean = false,
	): LapisBt.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			throw IllegalStateException("Can't start bluetooth server when bluetooth is off")
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		println("$$$$$ clients = $_clientSocketByAddress")

		_bluetoothServerSocketByServiceUuid[serviceUuid]?.also { serverSocket ->
			serverSocket.close()
		}
		_bluetoothServerSocketByServiceUuid.remove(serviceUuid)

		_activeBluetoothServersUuids.update {
			(it + serviceUuid).distinct()
		}

		val serverSocket = if (insecure) {
			adapter.listenUsingInsecureRfcommWithServiceRecord(
				serviceName,
				serviceUuid,
			)
		}
		else {
			adapter.listenUsingRfcommWithServiceRecord(
				serviceName,
				serviceUuid,
			)
		}

		_bluetoothServerSocketByServiceUuid[serviceUuid] = serverSocket

		val clientSocket = serverSocket.tryAccept()

		serverSocket.close()
		_bluetoothServerSocketByServiceUuid.remove(serviceUuid)
		_activeBluetoothServersUuids.update { it - serviceUuid }

		if (clientSocket == null) {
			return LapisBt.ConnectionResult.CouldNotConnect
		}

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = false,
			)
		)

		_devices.update { devices ->
			val androidBondedDevices = adapter.bondedDevices
			val isDeviceInList = devices.any { it.address == connectedDevice.address }
			if (isDeviceInList) {
				devices.map { device ->
					if (device.address == connectedDevice.address) {
						val isPaired = androidBondedDevices.find { it.address == device.address } != null
						device.copy(
							connectionState = BluetoothDevice.ConnectionState.Connected,
							pairingState = if (isPaired) {
								BluetoothDevice.PairingState.Paired
							}
							else device.pairingState,
						)
					}
					else device
				}
			}
			else devices + connectedDevice
		}

		updateDevices()

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	private suspend fun connectToDeviceInternal(
		deviceAddress: String,
		serviceUuid: UUID,
		insecure: Boolean = false,
	): LapisBt.ConnectionResult {
		requireValidAddress(deviceAddress)

		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			throw IllegalStateException("Can't connect to device when bluetooth if off")
		}

		println("$$$$$ connectToDevice insecure: $insecure")

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Connecting)
				}
				else device
			}
		}

		val androidDevice = adapter.getRemoteDevice(deviceAddress)

		val clientSocket = if (insecure) {
			androidDevice.createInsecureRfcommSocketToServiceRecord(serviceUuid)
		}
		else androidDevice.createRfcommSocketToServiceRecord(serviceUuid)

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

		if (clientSocket == null) {
			return LapisBt.ConnectionResult.CouldNotConnect
		}

		// Should we stop scan? It is recommended, but maybe that's up to the user of this library
		//stopScan()

		val isConnectionSuccessFull = clientSocket.tryConnect()
		if (!isConnectionSuccessFull) {
			_devices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			return LapisBt.ConnectionResult.CouldNotConnect
		}

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = true,
			)
		)

		val newDevices = _devices.updateAndGet { devices ->
			val androidBondedDevices = adapter.bondedDevices
			val isDeviceInList = devices.any { it.address == connectedDevice.address }
			if (isDeviceInList) {
				devices.map { device ->
					if (device.address == connectedDevice.address) {
						val isPaired = androidBondedDevices.find { it.address == device.address } != null
						device.copy(
							connectionState = BluetoothDevice.ConnectionState.Connected,
							pairingState = if (isPaired) {
								BluetoothDevice.PairingState.Paired
							}
							else device.pairingState,
						)
					}
					else device
				}
			}
			else devices + connectedDevice
		}

		println("$$$ connectToDeviceInternal: ${newDevices.filter { it.address == deviceAddress }}")

		updateDevices()

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}


	private suspend fun BluetoothServerSocket.tryAccept(): BluetoothSocket? {
		return withContext(Dispatchers.IO) {
			// Maybe we should set onCancelling = true
			val cancelHandler = coroutineContext.job.invokeOnCompletion { throwable ->
				if (throwable !is CancellationException) {
					return@invokeOnCompletion
				}
				try {
					this@tryAccept.close()
				}
				catch (_: IOException) {
				}
			}

			val clientSocket = try {
				ensureActive()
				// If device is not paired it will show a pop-up dialog to pair it
				println("$$$$$ tryAccept")
				accept()
			}
			catch (e: IOException) {
				println("$$$$$ tryAccept error: $e")
				null
			} finally {
				cancelHandler.dispose()
			}

			if (clientSocket == null) {
				close()
				return@withContext null
			}

			_clientSocketByAddress[clientSocket.remoteDevice.address] = clientSocket

			return@withContext clientSocket
		}
	}

	private suspend fun BluetoothSocket.tryConnect(): Boolean {
		return withContext(Dispatchers.IO) {
			val cancelHandler = coroutineContext.job.invokeOnCompletion { throwable ->
				if (throwable !is CancellationException) {
					return@invokeOnCompletion
				}
				try {
					this@tryConnect.close()
				}
				catch (_: IOException) {
				}
			}

			try {
				ensureActive()

				// If device is not paired it will show a pop-up dialog to pair it (if the connection is done securely)
				println("$$$$$ tryConnect")
				connect()
				return@withContext true
			}
			catch (e: IOException) {
				println("$$$$$ tryConnect error: $e")
				// This message can happen when you try to connect to a device that is not acting as a server (and probably in more cases),
				// but also sometimes it just throws the error when you try to connect, because of this
				// we try to connect again.
				if (e.message.orEmpty().contains("read failed, socket might closed or timeout")) {
					ensureActive()

					try {
						connect()
						return@withContext true
					}
					catch (e: IOException) {
						println("$$$ inner error: $e")
					}
				}
				close()
				return@withContext false
			}
			finally {
				cancelHandler.dispose()
			}
		}
	}

	private fun requireValidAddress(deviceAddress: String) {
		require(BluetoothAdapter.checkBluetoothAddress(deviceAddress)) {
			"The device address '$deviceAddress' is invalid"
		}
	}

	private fun AndroidBluetoothDevice.toModel(connectionState: BluetoothDevice.ConnectionState): BluetoothDevice {
		return BluetoothDevice(
			address = this.address,
			name = this.name,
			alias = if (Build.VERSION.SDK_INT >= 30) {
				this.alias
			}
			else this.name,
			addressType = if (Build.VERSION.SDK_INT >= 35) {
				when (this.addressType) {
					AndroidBluetoothDevice.ADDRESS_TYPE_PUBLIC -> BluetoothDevice.AddressType.Public
					AndroidBluetoothDevice.ADDRESS_TYPE_RANDOM -> BluetoothDevice.AddressType.Random
					AndroidBluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> BluetoothDevice.AddressType.Anonymous
					AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN -> BluetoothDevice.AddressType.Unknown
					else -> BluetoothDevice.AddressType.Unknown
				}
			}
			else BluetoothDevice.AddressType.NotSupported,
			type = when (this.bluetoothClass.majorDeviceClass) {
				AUDIO_VIDEO -> BluetoothDevice.Type.AudioVideo
				COMPUTER -> BluetoothDevice.Type.Computer
				HEALTH -> BluetoothDevice.Type.Health
				IMAGING -> BluetoothDevice.Type.Imaging
				WEARABLE -> BluetoothDevice.Type.Wearable
				MISC -> BluetoothDevice.Type.Misc
				PHONE -> BluetoothDevice.Type.Phone
				NETWORKING -> BluetoothDevice.Type.Networking
				TOY -> BluetoothDevice.Type.Toy
				PERIPHERAL -> BluetoothDevice.Type.Peripheral
				else -> BluetoothDevice.Type.Uncategorized
			},
			mode = when (this.type) {
				AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothDevice.Mode.Classic
				AndroidBluetoothDevice.DEVICE_TYPE_LE -> BluetoothDevice.Mode.Le
				AndroidBluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothDevice.Mode.Dual
				AndroidBluetoothDevice.DEVICE_TYPE_UNKNOWN -> BluetoothDevice.Mode.Unknown
				else -> BluetoothDevice.Mode.Unknown
			},
			uuids = this.uuids.orEmpty().map { it.uuid },
			pairingState = when (this.bondState) {
				BOND_BONDED -> BluetoothDevice.PairingState.Paired
				BOND_BONDING -> BluetoothDevice.PairingState.Pairing
				BOND_NONE -> BluetoothDevice.PairingState.None
				else -> BluetoothDevice.PairingState.None
			},
			connectionState = connectionState,
		)
	}
}
