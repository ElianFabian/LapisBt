package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.util.KeyedMutex
import com.elianfabian.lapisbt.util.toModel
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
	private val lapisAdapter: LapisBluetoothAdapter,
	private val androidHelper: AndroidHelper,
	private val bluetoothEvents: LapisBluetoothEvents,
) : LapisBt {

	private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	private val _isBluetoothConnectPermissionGranted = MutableStateFlow(androidHelper.isBluetoothConnectGranted())

	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val pairedDevices = _pairedDevices.asStateFlow()

	// FIXME: when a scanned device is in the connected state it also appears in paired devices, this should not happen
	// FIXME: it also happens that sometimes for one device it seems the other is connected, but from the other side it is disconnected, I don't know why
	//  they are actually connected, because I can send messages, we just have to see why it appears as disconnected.
	//  NOTES: It seems that the fake disconnection issue is related to this other issue:
	//  Sometimes in one device the pairing dialog appears for no reason, then it disappears, and then the second device has
	//  the first device as paired, but the first device doesn't have the second device paired.
	//  This is such a strange behaviour, no code of this library seemed to be executed when that happened, we'll have to see.
	//  When this happens the second device has the first device visually as disconnected.
	//  It seems that the dialog does not always appear, but the behavior still happens.
	//  It seems that it happens around 4-9 minutes after connection.
	//  For the first device it goes from the bonding state to the none state.
	//  For the second device it goes from the bonding state to the bonded state.
	//  As for the tests done, no matters who is the server at the beginning, always the same device
	//  is the one who has the other paired, so maybe it's a device specific issue.
	//  It also seems that some random device has been trying to connect to my first device,
	//  and when that happens the issue mentioned above occurs, but it seems
	//  this also happens without that random device trying to pair my first device.
	//  We have to also check why when the second device that now has the firs device paired it now visually looks like
	//  the first device is disconnected, even though it's not
	private val _scannedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val scannedDevices = _scannedDevices.asStateFlow()

	private val _connectedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val connectedDevices = _connectedDevices.asStateFlow()

	private val _events = MutableSharedFlow<LapisBt.Event>()
	override val events = _events.asSharedFlow()

	private val _bluetoothDeviceName = MutableStateFlow<String?>(
		if (canEnableBluetooth) {
			lapisAdapter.name
		}
		else null
	)
	override val bluetoothDeviceName = _bluetoothDeviceName.asStateFlow()

	override val isBluetoothSupported: Boolean
		get() = androidHelper.isBluetoothSupported()

	override val canEnableBluetooth: Boolean
		get() = androidHelper.isBluetoothConnectGranted()

	private val _bluetoothState = MutableStateFlow(
		if (lapisAdapter.isEnabled) {
			LapisBt.BluetoothState.On
		}
		else LapisBt.BluetoothState.Off
	)
	override val state = _bluetoothState.asStateFlow()

	private val _isScanning = MutableStateFlow(
		if (androidHelper.isBluetoothScanGranted()) {
			lapisAdapter.isDiscovering
		}
		else false
	)
	override val isScanning = _isScanning.asStateFlow()

	private val _activeBluetoothServersUuids = MutableStateFlow(emptyList<UUID>())
	override val activeBluetoothServersUuids = _activeBluetoothServersUuids.asStateFlow()

	private var _bluetoothServerSocketByServiceUuid: MutableMap<UUID, LapisBluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<String, LapisBluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<String, Job> = ConcurrentHashMap()
	private val _readMutex = KeyedMutex<String>()
	private val _writeMutex = KeyedMutex<String>()

	// This is to avoid duplicate disconnection events
	private val _skipDisconnectionEventForDevices = mutableSetOf<String>()


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
		return lapisAdapter.setName(newName)
	}

	// On some devices like Xiaomi Mi MIX 2S - API 29
	// This won't work unless the location is enabled
	override fun startScan(): Boolean {
		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		updateDevices()

		return lapisAdapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		return lapisAdapter.cancelDiscovery()
	}

	override fun clearScannedDevices() {
		_scannedDevices.value = emptyList()
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
		if (serviceUuid !in _bluetoothServerSocketByServiceUuid) {
			throw IllegalStateException("Attempted to stop a Bluetooth server that was not registered or already stopped (UUID: $serviceUuid).")
		}

		val serverSocket = _bluetoothServerSocketByServiceUuid[serviceUuid] ?: return
		_activeBluetoothServersUuids.update { activeBluetoothServersUuids ->
			activeBluetoothServersUuids.filter { uuid -> uuid != serviceUuid }
		}
		serverSocket.close()
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
		println("$$$ disconnectFromDevice called for $deviceAddress")
		if (!lapisAdapter.isEnabled) {
			return false
		}

		// TODO: maybe this is not necessary, since we can observe the disconnection in the bluetoothEvents

		// If the clientSocket is null it should mean it was already disconnected
		val clientSocket = _clientSocketByAddress[deviceAddress] ?: return false
		if (!clientSocket.isConnected) {
			return false
		}

		_pairedDevices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnecting)
				}
				else device
			}
		}
		_scannedDevices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnecting)
				}
				else device
			}
		}
		_connectedDevices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnecting)
				}
				else device
			}
		}

		val manuallyDisconnected = try {
			withContext(Dispatchers.IO) {
				clientSocket.outputStream.write(0)
			}
			true
		}
		catch (e: IOException) {
			if (e.message.orEmpty().contains("Broken pipe")) {
				false
			}
			else throw e
		}

		try {
			updateDevices()

			_events.emit(
				LapisBt.Event.OnDeviceDisconnected(
					disconnectedDevice = lapisAdapter.getRemoteDevice(deviceAddress).toModel(
						connectionState = BluetoothDevice.ConnectionState.Disconnected
					),
					manuallyDisconnected = manuallyDisconnected,
				)
			)

			_skipDisconnectionEventForDevices.add(deviceAddress)

			return true
		}
		catch (_: IOException) {
		}

		return false
	}

	override suspend fun cancelConnectionAttempt(deviceAddress: String): Boolean {
		requireValidAddress(deviceAddress)

		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!lapisAdapter.isEnabled) {
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

			_pairedDevices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			_scannedDevices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
		}
		catch (_: IOException) {
			return false
		}

		return true
	}

	override fun getRemoteDevice(deviceAddress: String): BluetoothDevice {
		requireValidAddress(deviceAddress)

		val pairedDevice = _pairedDevices.value.find { it.address == deviceAddress }
		if (pairedDevice != null) {
			return pairedDevice
		}

		val scannedDevice = _scannedDevices.value.find { it.address == deviceAddress }
		if (scannedDevice != null) {
			return scannedDevice
		}

		val connectedDevice = _connectedDevices.value.find { it.address == deviceAddress }
		if (connectedDevice != null) {
			return connectedDevice
		}

		val androidDevice = lapisAdapter.getRemoteDevice(deviceAddress)
		val isConnected = _clientSocketByAddress[deviceAddress]?.isConnected == true

		return androidDevice.toModel(
			connectionState = if (isConnected) {
				BluetoothDevice.ConnectionState.Connected
			}
			else BluetoothDevice.ConnectionState.Disconnected,
		)
	}

	// TODO: during development I've seen random pairings between my devices,
	//  I don't really know why, I don't think this function was even called in those
	//  cases. We'll have to check it out.
	// NOTES: when 2 devices are connected and we try to pair them the connection is closed
	override fun pairDevice(deviceAddress: String): Boolean {
		println("$$$$ LapisBtImpl.pairDevice: $deviceAddress")
		val device = lapisAdapter.getRemoteDevice(deviceAddress)
		return device.createBond()
	}

	// It seems that unpairing a device will force the disconnection
	@InternalBluetoothReflectionApi
	override fun unpairDevice(deviceAddress: String): Boolean {
		val device = lapisAdapter.getRemoteDevice(deviceAddress)
		return device.removeBond()
	}

	override suspend fun sendData(deviceAddress: String, action: suspend (stream: OutputStream) -> Unit): Boolean {
		requireValidAddress(deviceAddress)

		val clientSocket = _clientSocketByAddress[deviceAddress]

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return _readMutex.withLock(deviceAddress) {
			withContext(Dispatchers.IO) {
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
	}

	override suspend fun receiveData(deviceAddress: String, action: suspend (stream: InputStream) -> Unit): Boolean {
		requireValidAddress(deviceAddress)

		val clientSocket = _clientSocketByAddress[deviceAddress]

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return _writeMutex.withLock(deviceAddress) {
			withContext(Dispatchers.IO) {
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
	}


	// TODO: check if everything in this class was garbage-collected
	override fun dispose() {
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

		bluetoothEvents.dispose()
	}


	private fun initialize() {
		if (lapisAdapter.isEnabled) {
			updateDevices()
		}
		// Using the events to update these states makes the code a little cleaner,
		// but we'll have to see if it won't break anything
		_scope.launch {
			_events.collect { event ->
				when (event) {
					is LapisBt.Event.OnDeviceConnected -> {
						val connectedDevice = event.connectedDevice
						updateDevices()
						_connectedDevices.update { devices ->
							if (connectedDevice.address !in devices.map { it.address }) {
								devices + connectedDevice
							}
							else devices
						}
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						val disconnectedDevice = event.disconnectedDevice
						val clientSocket = _clientSocketByAddress[disconnectedDevice.address]
						clientSocket?.close()
						_clientSocketByAddress.remove(disconnectedDevice.address)
						_clientJobByAddress[disconnectedDevice.address]?.cancel()
						_clientJobByAddress.remove(disconnectedDevice.address)

						_pairedDevices.update { devices ->
							devices.map { device ->
								if (device.address == disconnectedDevice.address) {
									device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
								}
								else device
							}
						}
						_scannedDevices.update { devices ->
							devices.map { device ->
								if (device.address == disconnectedDevice.address) {
									device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
								}
								else device
							}
						}
						_connectedDevices.update { devices ->
							devices.filter { it.address != disconnectedDevice.address }
						}
					}
					is LapisBt.Event.OnDeviceScanned -> {
						// no-op
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.onActivityResumed.collect {
				updateDevices()

				_isBluetoothConnectPermissionGranted.value = androidHelper.isBluetoothConnectGranted()

				if (canEnableBluetooth) {
					_bluetoothDeviceName.value = lapisAdapter.name
				}
			}
		}
		_scope.launch {
			bluetoothEvents.bluetoothStateFlow.collect { state ->
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
		}
		_scope.launch {
			bluetoothEvents.deviceAliasChangeFlow.collect { lapisDevice ->
				_pairedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(alias = lapisDevice.alias)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(alias = lapisDevice.alias)
						}
						else device
					}
				}
				_connectedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(alias = lapisDevice.alias)
						}
						else device
					}
				}
			}
		}
		_scope.launch {
			// NOTES: it is possible that a device is paired with us but we don't have it in our pairedDevices list because we
			// unpaired it, and when we try to pair with it it will show an error in the UI, but I'm not sure if we can
			// reliably detect that case here
			// It also seems that they can't connect when this happens, it doesn't seem we can detect reliably detect this
			// If the device who has the other device unpaired tries to pair it will show a UI error, if the other device tries nothing will happen
			// The device that has the other device paired when it scans for other devices the paired device will appear as scanned
			bluetoothEvents.deviceBondStateChangeFlow.collect { lapisDevice ->

				println("$$$ Device bond state changed: $lapisDevice")

				_scannedDevices.update { devices ->
					val updatedDevices = devices.mapNotNull { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED) {
								return@mapNotNull null
							}
							existingDevice.copy(
								pairingState = when (lapisDevice.bondState) {
									AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
									AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
									AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
									else -> BluetoothDevice.PairingState.None
								},
							)
						}
						else existingDevice
					}

//					if (lapisDevice.address !in updatedDevices.map { it.address } && lapisDevice.bondState != AndroidBluetoothDevice.BOND_BONDED) {
//						val newDevice = lapisDevice.toModel(
//							connectionState = BluetoothDevice.ConnectionState.Disconnected,
//						)
//						updatedDevices + newDevice
//					}
//					else updatedDevices

					updatedDevices
				}
				_pairedDevices.update { devices ->
					val updatedDevices = devices.mapNotNull { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_NONE) {
								return@mapNotNull null
							}
							existingDevice.copy(
								pairingState = when (lapisDevice.bondState) {
									AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
									AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
									else -> error("Impossible bonding state at the point of execution: ${lapisDevice.bondState}")
								},
							)
						}
						else existingDevice
					}

					if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED && lapisDevice.address !in devices.map { it.address } && lapisDevice.address !in _scannedDevices.value.map { it.address }) {
						val newDevice = lapisDevice.toModel(
							connectionState = BluetoothDevice.ConnectionState.Disconnected,
						)
						println("$$$ newPairedDevice: $lapisDevice")
						updatedDevices + newDevice
					}
					else updatedDevices
				}
				_connectedDevices.update { devices ->
					devices.mapNotNull { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED) {
								return@mapNotNull null
							}
							existingDevice.copy(
								pairingState = when (lapisDevice.bondState) {
									AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
									AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
									AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
									else -> BluetoothDevice.PairingState.None
								},
							)
						}
						else existingDevice
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.deviceDisconnectedFlow.collect { disconnectedDevice ->
				if (disconnectedDevice.address in _skipDisconnectionEventForDevices) {
					return@collect
				}

				_events.emit(
					LapisBt.Event.OnDeviceDisconnected(
						disconnectedDevice = disconnectedDevice.toModel(
							connectionState = BluetoothDevice.ConnectionState.Disconnected
						),
						manuallyDisconnected = false,
					)
				)
			}
		}
		_scope.launch {
			bluetoothEvents.deviceNameFlow.collect { newName ->
				_bluetoothDeviceName.value = newName
			}
		}
		_scope.launch {
			bluetoothEvents.deviceUuidsChangeFlow.collect { lapisDevice ->
				_pairedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(uuids = lapisDevice.uuids)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(uuids = lapisDevice.uuids)
						}
						else device
					}
				}
				_connectedDevices.update { devices ->
					devices.map { device ->
						if (device.address == lapisDevice.address) {
							device.copy(uuids = lapisDevice.uuids)
						}
						else device
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.deviceFoundFlow.collect { lapisDevice ->
				val newDevice = lapisDevice.toModel(
					connectionState = BluetoothDevice.ConnectionState.Disconnected,
				)
				//println("$$$ deviceFound = $newDevice")

				if (newDevice.address in _pairedDevices.value.map { it.address }) {
					// This probably means we had a paired device, but that device
					// removed us from its own paired-devices list.
					// In this case, we can remove it from our paired-devices list as well.
					// This way we avoid connection or pairing issues.
					// Maybe in future we consider a different approach or consider that this
					// should be handled by the user of this library.
					// Maybe when try to connect to a device, and it fails we could silently scan for devices,
					// check if it appears as a scanned device and do the same, but I'm not sure if this is something
					// we should actually do here

					// For now, we'll uncomment this, I think this should be the responsibility of the user of this library.
					//unpairDevice(newDevice.address)
				}

				_events.emit(LapisBt.Event.OnDeviceScanned(newDevice))

				_scannedDevices.update { devices ->
					val updatedDevices = devices.map { device ->
						if (device.address == newDevice.address) {
							newDevice
						}
						else device
					}
					if (updatedDevices.any { it.address == newDevice.address }) {
						updatedDevices
					}
					else updatedDevices + newDevice
				}
			}
		}
		_scope.launch {
			bluetoothEvents.isDiscoveringFlow.collect { isDiscovering ->
				_isScanning.value = isDiscovering
			}
		}

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

					_pairedDevices.update { devices ->
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
					_scannedDevices.update { devices ->
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
					//println("$$$ Bluetooth is ON, devices updated.")
				}
				if (isBluetoothConnectPermissionGranted) {
					_bluetoothDeviceName.value = lapisAdapter.name
				}
			}.collect()
		}
	}

	private fun updateDevices() {
		if (!canEnableBluetooth) {
			return
		}

		_pairedDevices.update { devices ->
			lapisAdapter.getBondedDevices().orEmpty().map { lapisDevice ->
				lapisDevice.toModel(
					connectionState = when (lapisDevice.address) {
						in _clientSocketByAddress -> BluetoothDevice.ConnectionState.Connected
						in devices.map { it.address } -> devices.first { it.address == lapisDevice.address }.connectionState
						else -> BluetoothDevice.ConnectionState.Disconnected
					},
				)
			}
		}

		_scannedDevices.updateAndGet { devices ->
			devices.map { device ->
				if (_clientSocketByAddress.contains(device.address)) {
					device.copy(
						connectionState = BluetoothDevice.ConnectionState.Connected,
					)
				}
				else device
			}
		}

		_scannedDevices.updateAndGet { devices ->
			devices.mapNotNull { device ->
				if (_clientSocketByAddress.contains(device.address)) {
					device
				}
				else null
			}
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
		if (!lapisAdapter.isEnabled) {
			throw IllegalStateException("Can't start bluetooth server when bluetooth is off")
		}

		_bluetoothServerSocketByServiceUuid[serviceUuid]?.also { serverSocket ->
			serverSocket.close()
		}
		_bluetoothServerSocketByServiceUuid.remove(serviceUuid)

		_activeBluetoothServersUuids.update {
			(it + serviceUuid).distinct()
		}

		val serverSocket = if (insecure) {
			lapisAdapter.listenUsingInsecureRfcommWithServiceRecord(
				serviceName,
				serviceUuid,
			)
		}
		else {
			lapisAdapter.listenUsingRfcommWithServiceRecord(
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

		updateDevices()

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = false,
			)
		)

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	// It seems that when we connect to a device, for both the bond state changes to bonding and then none,
	// which seems very weird.
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

		_pairedDevices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Connecting)
				}
				else device
			}
		}
		_scannedDevices.update { devices ->
			devices.map { device ->
				if (device.address == deviceAddress) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Connecting)
				}
				else device
			}
		}

		val androidDevice = lapisAdapter.getRemoteDevice(deviceAddress)

		val clientSocket = if (insecure) {
			androidDevice.createInsecureRfcommSocketToServiceRecord(serviceUuid)
		}
		else androidDevice.createRfcommSocketToServiceRecord(serviceUuid)

		println("$$$ connectToDeviceInternal.clientSocket: $clientSocket")

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket


		println("$$$ start connectToDeviceInternal")
		val isConnectionSuccessFull = clientSocket.tryConnect()
		println("$$$ connectToDeviceInternal.isConnectionSuccessFull: $isConnectionSuccessFull")

		if (!isConnectionSuccessFull) {
			_pairedDevices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			_scannedDevices.update { devices ->
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

		updateDevices()

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = true,
			)
		)

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}


	private suspend fun LapisBluetoothServerSocket.tryAccept(): LapisBluetoothSocket? {
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
				accept()
			}
			catch (_: IOException) {
				null
			}
			finally {
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

	private suspend fun LapisBluetoothSocket.tryConnect(): Boolean {
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
				connect()
				return@withContext true
			}
			catch (e: IOException) {
				// This message can happen when you try to connect to a device that is not acting as a server (and probably in more cases),
				// but also sometimes it just throws the error when you try to connect, because of this
				// we try to connect again.
				if (e.message.orEmpty().contains("read failed, socket might closed or timeout")) {
					ensureActive()

					try {
						println("$$$ connect 2")
						connect()
						return@withContext true
					}
					catch (e: IOException) {
						println("$$$ e1: ${e.message}")
					}
				}
				close()
				println("$$$ e2: ${e.message}")
				return@withContext false
			}
			finally {
				cancelHandler.dispose()
			}
		}
	}

	private fun requireValidAddress(deviceAddress: String) {
		require(LapisBt.checkBluetoothAddress(deviceAddress)) {
			"The device address '$deviceAddress' is invalid"
		}
	}
}
