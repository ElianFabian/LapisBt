package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.util.KeyedMutex
import com.elianfabian.lapisbt.util.checkBluetoothAddressInternal
import com.elianfabian.lapisbt.util.convertToScanMode
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// TODO: add a better logging system
internal class LapisBtImpl(
	private val lapisAdapter: LapisBluetoothAdapter,
	private val androidHelper: AndroidHelper,
	private val bluetoothEvents: LapisBluetoothEvents,
) : LapisBt {

	private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	private var _isDisposed = false

	private val _isConnectPermissionGranted = MutableStateFlow(androidHelper.isBluetoothConnectGranted())
	private val _isScanPermissionGranted = MutableStateFlow(androidHelper.isBluetoothScanGranted())

	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val pairedDevices = _pairedDevices.asStateFlow()

	private val _scannedDevices = MutableStateFlow(emptyList<ScannedBluetoothDevice>())
	override val scannedDevices = _scannedDevices.asStateFlow()

	private val _connectedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val connectedDevices = _connectedDevices.asStateFlow()

	private val _events = MutableSharedFlow<LapisBt.Event>(extraBufferCapacity = 64)
	override val events = _events.distinctUntilChangedBy { event ->
		when (event) {
			// We prevent having duplicate connection/disconnection events
			// Maybe it is possible to manually handle it, but it seems very hard to do
			is LapisBt.Event.OnDeviceConnected, is LapisBt.Event.OnDeviceDisconnected -> {
				"${event.device.address}-${event::class}"
			}
			else -> Any()
		}
	}
		.shareIn(
			scope = _scope,
			started = SharingStarted.Eagerly,
		)

	private val _bluetoothDeviceName = MutableStateFlow<String?>(
		if (androidHelper.isBluetoothConnectGranted()) {
			lapisAdapter.name
		}
		else null
	)
	override val bluetoothDeviceName = _bluetoothDeviceName.asStateFlow()

	override val isBluetoothClassicSupported: Boolean
		get() = androidHelper.isBluetoothClassicSupported()

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

	private val _scanMode = MutableStateFlow(
		if (androidHelper.isBluetoothConnectGranted()) {
			convertToScanMode(lapisAdapter.scanMode)
		}
		else LapisBt.ScanMode.None
	)
	override val scanMode = _scanMode.asStateFlow()

	private val _activeBluetoothServersUuids = MutableStateFlow(emptyList<UUID>())
	override val activeBluetoothServersUuids = _activeBluetoothServersUuids.asStateFlow()

	private var _bluetoothServerSocketByServiceUuid: MutableMap<UUID, LapisBluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<BluetoothDevice.Address, LapisBluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<BluetoothDevice.Address, Job> = ConcurrentHashMap()
	private val _readMutex = KeyedMutex<BluetoothDevice.Address>()
	private val _writeMutex = KeyedMutex<BluetoothDevice.Address>()

	// This is to avoid duplicate disconnection events
	private val _skipDisconnectionEventForDevices = Collections.synchronizedCollection(mutableSetOf<BluetoothDevice.Address>())

	// This is to know which device stared the bonding process
	private val _pairingsStarted = Collections.synchronizedCollection(mutableSetOf<BluetoothDevice.Address>())

	// Unpairings implicitly forces disconnection
	private val _unpairingsStarted = Collections.synchronizedCollection(mutableSetOf<BluetoothDevice.Address>())

	// This is to check unexpected bonded devices
	// There's more information about this in the code below
	private val _incomingPairingRequests = Collections.synchronizedCollection(mutableSetOf<BluetoothDevice.Address>())


	init {
		_scope.launch {
			_scannedDevices.collect { scannedDevices ->
				println("$$$ scannedDevice: ${scannedDevices.groupingBy { it.device.address.value }.eachCount()}")
			}
		}
		_scope.launch {
			_connectedDevices.collect { scannedDevices ->
				println("$$$ connectedDevices: ${scannedDevices.groupingBy { it.address.value }.eachCount()}")
			}
		}
		_scope.launch {
			_pairedDevices.collect { scannedDevices ->
				println("$$$ pairedDevices: ${scannedDevices.groupingBy { it.address.value }.eachCount()}")
			}
		}
		initialize()
	}


	override fun setBluetoothDeviceName(newName: String): Boolean {
		checkIsNotDispose()

		if (!androidHelper.isBluetoothConnectGranted()) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}

		// Not all devices support changing the Bluetooth name, and there doesn't seem to be a way to check it
		// Here's a list of devices that we tested that support it:
		// - Google
		// - Motorola
		// - Sony Xperia 10 (34)
		// - SHARP AQUOS
		// - ZTE
		// - FUJITSU
		//
		// And here's a list of devices that I tested that "don't" support it:
		// - Huawei
		// - Xiaomi
		// - Realme
		// - Samsung
		//
		// Notes:
		// - Bluetooth must be enabled to change the name
		// - Immediately calling BluetoothAdapter.getName() after calling BluetoothAdapter.setName(...) won't return the new name
		// - When we set the name on a device that does not support it the change will be visible during scanning,
		// but if we go to settings the name will reset to the previous one (but it will persist during app or phone restarts, at least on Realme)
		return lapisAdapter.setName(newName)
	}

	// On some devices like Xiaomi Mi MIX 2S - API 29
	// This won't work unless the location is enabled
	override fun startScan(): Boolean {
		checkIsNotDispose()

		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		updateDevices()

		return lapisAdapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		checkIsNotDispose()

		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		return lapisAdapter.cancelDiscovery()
	}

	override fun clearScannedDevices() {
		checkIsNotDispose()

		_scannedDevices.value = emptyList()
	}

	override suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override fun stopBluetoothServer(serviceUuid: UUID) {
		checkIsNotDispose()

		if (serviceUuid !in _bluetoothServerSocketByServiceUuid) {
			throw IllegalStateException("Attempted to stop a Bluetooth server that was not registered or already stopped (UUID: $serviceUuid).")
		}

		val serverSocket = _bluetoothServerSocketByServiceUuid[serviceUuid] ?: return
		_activeBluetoothServersUuids.update { activeBluetoothServersUuids ->
			activeBluetoothServersUuids.filter { uuid -> uuid != serviceUuid }
		}
		serverSocket.close()
	}

	override suspend fun connectToDevice(deviceAddress: BluetoothDevice.Address, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun connectToDeviceWithoutPairing(deviceAddress: BluetoothDevice.Address, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override suspend fun disconnectFromDevice(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDispose()

		if (!androidHelper.isBluetoothConnectGranted()) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		println("$$$ disconnectFromDevice called for $deviceAddress")
		if (!lapisAdapter.isEnabled) {
			return false
		}

		// If the clientSocket is null it should mean it was already disconnected
		val clientSocket = _clientSocketByAddress[deviceAddress] ?: return false
		if (!clientSocket.isConnected) {
			return false
		}

		_skipDisconnectionEventForDevices.add(deviceAddress)

		val disconnectedLocally = try {
			println("$$$ disconnectFromDevice($deviceAddress): try1")
			withContext(Dispatchers.IO) {
				clientSocket.outputStream.write(0)
			}
			println("$$$ disconnectFromDevice($deviceAddress): try2")
			true
		}
		catch (e: IOException) {
			println("$$$$ disconnectFromDevice catch: $e")
			if (e.message.orEmpty().contains("Broken pipe")) {
				false
			}
			else {
				_skipDisconnectionEventForDevices.remove(deviceAddress)
				throw e
			}
		}

		updateDevices()

		// If we put this skip disconnection event logic inside handleDisconnectedDevice
		// it doesn't work, probably because of concurrency issues
		if (!handleDisconnectedDevice(deviceAddress)) {
			_skipDisconnectionEventForDevices.remove(deviceAddress)
			return false
		}

		val disconnectedDevice = getRemoteDeviceInternal(deviceAddress)

		println("$$$$ Device disconnected(locally = $disconnectedLocally | 1): $disconnectedDevice")

		_events.tryEmit(
			LapisBt.Event.OnDeviceDisconnected(
				device = disconnectedDevice,
				disconnectedLocally = disconnectedLocally,
			)
		).also {
			println("$$$ disconnectFromDevice($deviceAddress).disconnection: ${it.hashCode()}")
		}

		_skipDisconnectionEventForDevices.remove(deviceAddress)

		println("$$$ end of disconnectFromDevice: $deviceAddress")

		return true
	}

	override suspend fun cancelConnectionAttempt(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDispose()

		println("$$$ cancelConnectionAttempt: $deviceAddress")

		if (!androidHelper.isBluetoothConnectGranted()) {
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
				devices.map { scannedDevice ->
					if (scannedDevice.device.address == deviceAddress) {
						scannedDevice.copy(
							device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						)
					}
					else scannedDevice
				}
			}
		}
		catch (_: IOException) {
			return false
		}

		return true
	}

	override fun getRemoteDevice(deviceAddress: BluetoothDevice.Address): BluetoothDevice {
		checkIsNotDispose()

		return getRemoteDeviceInternal(deviceAddress)
	}

	private fun getRemoteDeviceInternal(deviceAddress: BluetoothDevice.Address): BluetoothDevice {
		checkIsNotDispose()

		val pairedDevice = _pairedDevices.value.find { it.address == deviceAddress }
		if (pairedDevice != null) {
			return pairedDevice
		}

		val scannedDevice = _scannedDevices.value.find { it.device.address == deviceAddress }
		if (scannedDevice != null) {
			return scannedDevice.device
		}

		val connectedDevice = _connectedDevices.value.find { it.address == deviceAddress }
		if (connectedDevice != null) {
			return connectedDevice
		}

		val androidDevice = lapisAdapter.getRemoteDevice(deviceAddress.value)
		val isConnected = _clientSocketByAddress[deviceAddress]?.isConnected == true

		return androidDevice.toModel(
			connectionState = if (isConnected) {
				BluetoothDevice.ConnectionState.Connected
			}
			else BluetoothDevice.ConnectionState.Disconnected,
		)
	}

	override fun startDevicePairing(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDispose()

		println("$$$$ LapisBtImpl.startDevicePairing: $deviceAddress")
		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.createBond()) {
			_scannedDevices.update { devices ->
				devices.map { scannedDevice ->
					if (scannedDevice.device.address == deviceAddress) {
						scannedDevice.copy(
							device = scannedDevice.device.copy(pairingState = BluetoothDevice.PairingState.Pairing)
						)
					}
					else scannedDevice
				}
			}
			_connectedDevices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(pairingState = BluetoothDevice.PairingState.Pairing)
					}
					else device
				}
			}

			println("$$$ pairingStarted1: $deviceAddress")
			_pairingsStarted.add(deviceAddress)
			println("$$$ pairingStarted2: $deviceAddress")

			return true
		}

		return false
	}

	@InternalBluetoothReflectionApi
	override fun unpairDevice(deviceAddress: BluetoothDevice.Address): Boolean {
		println("$$$ unpairDevice: $deviceAddress")
		checkIsNotDispose()

		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.removeBond()) {
			_unpairingsStarted.add(deviceAddress)
			return true
		}

		return false
	}

	@InternalBluetoothReflectionApi
	override fun cancelPairingAttempt(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDispose()

		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.cancelBondProcess()) {
			_pairingsStarted.remove(deviceAddress)
			return true
		}
		return false
	}

	override suspend fun sendData(deviceAddress: BluetoothDevice.Address, action: suspend (stream: OutputStream) -> Unit): Boolean {
		checkIsNotDispose()

		val clientSocket = _clientSocketByAddress[deviceAddress]

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return _writeMutex.withLock(deviceAddress) {
			withContext(Dispatchers.IO) {
				ensureActive()

				try {
					action(clientSocket.outputStream)
				}
				catch (e: IOException) {
					println("$$$ sendData error: $e")
					if (!_isDisposed) {
						println("$$$$ Device disconnected(locally = false | 2): ${getRemoteDeviceInternal(deviceAddress)}")
						_skipDisconnectionEventForDevices.add(deviceAddress)
						if (!handleDisconnectedDevice(deviceAddress)) {
							_skipDisconnectionEventForDevices.remove(deviceAddress)
							return@withContext false
						}
						_scope.launch {
							_events.emit(
								LapisBt.Event.OnDeviceDisconnected(
									device = getRemoteDeviceInternal(deviceAddress),
									disconnectedLocally = _unpairingsStarted.contains(deviceAddress),
								)
							)
						}

						_skipDisconnectionEventForDevices.remove(deviceAddress)
						_unpairingsStarted.remove(deviceAddress)
					}
					return@withContext false
				}

				return@withContext true
			}
		}
	}

	override suspend fun receiveData(deviceAddress: BluetoothDevice.Address, action: suspend (stream: InputStream) -> Unit): Boolean {
		checkIsNotDispose()

		val clientSocket = _clientSocketByAddress[deviceAddress]

		println("$$$ receiveData($deviceAddress) = $clientSocket | ${clientSocket?.isConnected}")

		if (clientSocket == null || !clientSocket.isConnected) {
			return false
		}

		return _readMutex.withLock(deviceAddress) {
			withContext(Dispatchers.IO) {
				ensureActive()

				try {
					action(clientSocket.inputStream)
				}
				catch (e: IOException) {
					println("$$$ receiveData error($deviceAddress): $e | $_unpairingsStarted")
					if (!_isDisposed) {
						println("$$$$ Device disconnected(locally = false | 3): ${getRemoteDeviceInternal(deviceAddress)}")
						_skipDisconnectionEventForDevices.add(deviceAddress)
						if (!handleDisconnectedDevice(deviceAddress)) {
							_skipDisconnectionEventForDevices.remove(deviceAddress)
							return@withContext false
						}
						println("$$$ receiveData.onDeviceDisconnected1: $deviceAddress")
						_scope.launch {
							try {
								_events.emit(
									LapisBt.Event.OnDeviceDisconnected(
										device = getRemoteDeviceInternal(deviceAddress),
										disconnectedLocally = _unpairingsStarted.contains(deviceAddress),
									).also {
										println("$$$ receiveData($deviceAddress): ${it.hashCode()}")
									}
								)
							}
							catch (e: CancellationException) {
								println("$$$ receiveData.cancellation: $e")
								throw e
							}
							catch (e: Throwable) {
								println("$$$ receiveData.throwable: $e")
							}
							println("$$$ receiveData.onDeviceDisconnected2: $deviceAddress")
						}

						_skipDisconnectionEventForDevices.remove(deviceAddress)
						_unpairingsStarted.remove(deviceAddress)
					}
					return@withContext false
				}

				return@withContext true
			}
		}
	}


	// TODO: check if everything in this class was garbage-collected
	override fun dispose() {
		if (_isDisposed) {
			return
		}

		_isDisposed = true

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
		_scope.launch {
			bluetoothEvents.onActivityResumed.collect {
				updateDevices()

				_isConnectPermissionGranted.value = androidHelper.isBluetoothConnectGranted()
				_isScanPermissionGranted.value = androidHelper.isBluetoothScanGranted()

				if (androidHelper.isBluetoothConnectGranted()) {
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
						if (device.address.value == lapisDevice.address) {
							device.copy(alias = lapisDevice.alias)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { scannedDevice ->
						if (scannedDevice.device.address.value == lapisDevice.address) {
							scannedDevice.copy(
								device = scannedDevice.device.copy(alias = lapisDevice.alias)
							)
						}
						else scannedDevice
					}
				}
				_connectedDevices.update { devices ->
					devices.map { device ->
						if (device.address.value == lapisDevice.address) {
							device.copy(alias = lapisDevice.alias)
						}
						else device
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.deviceBondStateChangeFlow
				.distinctUntilChangedBy { device ->
					"${device.address}|${device.bondState}"
				}
				.collect { bondStateChangeLapisDevice ->
					println("$$$ Device bond state changed(${bondStateChangeLapisDevice.bondState}): $bondStateChangeLapisDevice")

					val targetDeviceAddress = BluetoothDevice.Address(bondStateChangeLapisDevice.address)

					_scannedDevices.update { devices ->
						devices.map { existingScannedDevice ->
							if (existingScannedDevice.device.address == targetDeviceAddress) {
								val existingDevice = existingScannedDevice.device
								val connectionState = if (existingDevice.connectionState == BluetoothDevice.ConnectionState.Connecting) {
									BluetoothDevice.ConnectionState.Connecting
								}
								else {
									if (_clientSocketByAddress[existingDevice.address]?.isConnected == true) {
										BluetoothDevice.ConnectionState.Connected
									}
									else BluetoothDevice.ConnectionState.Disconnected
								}
								if (bondStateChangeLapisDevice.bondState == AndroidBluetoothDevice.BOND_NONE) {
									// When a device is unbonded some data is erased, this way we can preserve it
									existingScannedDevice.copy(
										device = existingDevice.copy(
											connectionState = connectionState,
											pairingState = BluetoothDevice.PairingState.None,
										)
									)
								}
								else existingScannedDevice.copy(device = bondStateChangeLapisDevice.toModel(connectionState = connectionState))
							}
							else existingScannedDevice
						}
					}
					_pairedDevices.update { devices ->
						val existingDevice = devices.firstOrNull { it.address.value == bondStateChangeLapisDevice.address }
						println("$$$ bonsStateChange.existing: $existingDevice")
						if (existingDevice != null) {
							lapisAdapter.getBondedDevices().orEmpty().map { lapisDevice ->
								val connectionState = if (existingDevice.connectionState == BluetoothDevice.ConnectionState.Connecting) {
									BluetoothDevice.ConnectionState.Connecting
								}
								else {
									if (_clientSocketByAddress[existingDevice.address]?.isConnected == true) {
										BluetoothDevice.ConnectionState.Connected
									}
									else BluetoothDevice.ConnectionState.Disconnected
								}

								lapisDevice.toModel(connectionState = connectionState)
							}
						}
						else {
							lapisAdapter.getBondedDevices().orEmpty().map { lapisDevice ->
								val connectionState = if (_clientSocketByAddress[BluetoothDevice.Address(lapisDevice.address)]?.isConnected == true) {
									BluetoothDevice.ConnectionState.Connected
								}
								else BluetoothDevice.ConnectionState.Disconnected
								lapisDevice.toModel(connectionState = connectionState)
							}
						}
					}
					_connectedDevices.update { devices ->
						devices.map { existingDevice ->
							if (existingDevice.address.value == bondStateChangeLapisDevice.address) {
								val connectionState = if (existingDevice.connectionState == BluetoothDevice.ConnectionState.Connecting) {
									BluetoothDevice.ConnectionState.Connecting
								}
								else {
									if (_clientSocketByAddress[existingDevice.address]?.isConnected == true) {
										BluetoothDevice.ConnectionState.Connected
									}
									else BluetoothDevice.ConnectionState.Disconnected
								}
								bondStateChangeLapisDevice.toModel(connectionState = connectionState)
							}
							else existingDevice
						}
					}

					// During testing with a Realme 6 API 30 device while this device is actively connected (without bonding),
					// a third-party device may initiate a pairing request (with the device we're connected to).
					// Even if that request is rejected or times out,
					// the Android Bluetooth stack can incorrectly
					// transition the *current connected device* into a BONDED state on this side only.
					//
					// This results in an inconsistent state where:
					// - This device reports the peer as BONDED
					// - The peer device does NOT consider itself bonded
					//
					// The bonded device in this case did not explicitly request pairing with us.
					// We treat this as a spurious/ghost bond caused by the stack and ignore it.
					println("$$$ bondState.started: $_pairingsStarted")
					println("$$$ bondState.incoming: $_incomingPairingRequests")
					if (
						bondStateChangeLapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED
						&& targetDeviceAddress !in _incomingPairingRequests
						&& targetDeviceAddress !in _pairingsStarted
					) {
						Log.w(TAG, "Unexpected bonded device ${bondStateChangeLapisDevice.address}. This is very likely a Bluetooth stack bug, and the bonded device didn't actually try to pair with this device.")

						_events.emit(
							LapisBt.Event.OnUnexpectedDevicePaired(getRemoteDeviceInternal(targetDeviceAddress))
						)
					}
					else if (bondStateChangeLapisDevice.bondState != AndroidBluetoothDevice.BOND_BONDING && targetDeviceAddress in _incomingPairingRequests) {
						_incomingPairingRequests.remove(targetDeviceAddress)
					}
				}
		}
		_scope.launch {
			bluetoothEvents.unbondReasonFlow.collect { unbondReason ->
				val reason = when (unbondReason.reason) {
					AndroidInternalConstants.UNBOND_REASON_AUTH_FAILED -> LapisBt.Event.OnPairingFailed.Reason.AuthFailed
					AndroidInternalConstants.UNBOND_REASON_AUTH_REJECTED -> LapisBt.Event.OnPairingFailed.Reason.AuthRejected
					AndroidInternalConstants.UNBOND_REASON_AUTH_CANCELED -> LapisBt.Event.OnPairingFailed.Reason.AuthCanceled
					AndroidInternalConstants.UNBOND_REASON_REMOTE_DEVICE_DOWN -> LapisBt.Event.OnPairingFailed.Reason.RemoteDeviceDown
					AndroidInternalConstants.UNBOND_REASON_DISCOVERY_IN_PROGRESS -> LapisBt.Event.OnPairingFailed.Reason.DiscoveryInProgress
					AndroidInternalConstants.UNBOND_REASON_AUTH_TIMEOUT -> LapisBt.Event.OnPairingFailed.Reason.AuthTimeout
					AndroidInternalConstants.UNBOND_REASON_REPEATED_ATTEMPTS -> LapisBt.Event.OnPairingFailed.Reason.RepeatedAttempts
					AndroidInternalConstants.UNBOND_REASON_REMOTE_AUTH_CANCELED -> LapisBt.Event.OnPairingFailed.Reason.RemoteAuthCanceled
					AndroidInternalConstants.UNBOND_REASON_REMOVED -> {
						_unpairingsStarted.remove(BluetoothDevice.Address(unbondReason.androidDevice.address))
						LapisBt.Event.OnPairingFailed.Reason.Removed
					}
					else -> error("Impossible value for unbond reason: ${unbondReason.reason}")
				}

				println("$$$$ unbondReason: $reason")

				val device = getRemoteDeviceInternal(BluetoothDevice.Address(unbondReason.androidDevice.address))

				_events.emit(
					LapisBt.Event.OnPairingFailed(
						device = device,
						reason = reason,
					)
				)
			}
		}
		_scope.launch {
			bluetoothEvents.deviceDisconnectedFlow.collect { disconnectedAndroidDevice ->
				val targetDeviceAddress = BluetoothDevice.Address(disconnectedAndroidDevice.address)

				println("$$$ disconnectedDevice(${_clientSocketByAddress[targetDeviceAddress]?.isConnected}): $disconnectedAndroidDevice | $_skipDisconnectionEventForDevices")

				println("$$$ skip disconnection events = $_skipDisconnectionEventForDevices")
				if (targetDeviceAddress in _skipDisconnectionEventForDevices) {
					_skipDisconnectionEventForDevices.remove(targetDeviceAddress)
					return@collect
				}

				// Disconnection events aren't always reliable, they might get fired
				// even when trying to bond, and if no action is taking the dialog
				// times out, and then this event happens.
				// To solve it we just manually check the corresponding client socket.
				if (targetDeviceAddress !in _clientSocketByAddress) {
					return@collect
				}

				val disconnectedDevice = getRemoteDeviceInternal(targetDeviceAddress)

				if (!handleDisconnectedDevice(targetDeviceAddress)) {
					return@collect
				}

				println("$$$$ Device disconnected(locally = ${_unpairingsStarted.contains(targetDeviceAddress)} | 4): $disconnectedDevice")

				_events.emit(
					LapisBt.Event.OnDeviceDisconnected(
						device = disconnectedDevice,
						disconnectedLocally = _unpairingsStarted.contains(targetDeviceAddress),
					).also {
						println("$$$ disconnectionFlow(${disconnectedDevice.address}): ${it.hashCode()}")
					}
				)

				_unpairingsStarted.remove(targetDeviceAddress)
			}
		}
		_scope.launch {
			bluetoothEvents.deviceNameFlow.collect { newName ->
				_bluetoothDeviceName.value = newName
			}
		}
		_scope.launch {
			bluetoothEvents.deviceUuidsChangeFlow.collect { lapisDevice ->
				val targetDeviceAddress = BluetoothDevice.Address(lapisDevice.address)

				_pairedDevices.update { devices ->
					devices.map { device ->
						if (device.address == targetDeviceAddress) {
							device.copy(uuids = lapisDevice.uuids)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { scannedDevice ->
						if (scannedDevice.device.address == targetDeviceAddress) {
							scannedDevice.copy(
								device = scannedDevice.device.copy(uuids = lapisDevice.uuids)
							)
						}
						else scannedDevice
					}
				}
				_connectedDevices.update { devices ->
					devices.map { device ->
						if (device.address == targetDeviceAddress) {
							device.copy(uuids = lapisDevice.uuids)
						}
						else device
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.deviceFoundFlow.collect { (lapisDevice, rssi) ->
				val newDevice = lapisDevice.toModel(
					connectionState = BluetoothDevice.ConnectionState.Disconnected,
				)

				_events.emit(
					LapisBt.Event.OnDeviceScanned(
						device = newDevice,
						rssi = rssi,
					)
				)
				_scannedDevices.update { devices ->
					val updatedDevices = devices.map { scannedDevice ->
						if (scannedDevice.device.address == newDevice.address) {
							scannedDevice.copy(device = newDevice, rssi = rssi)
						}
						else scannedDevice
					}
					if (updatedDevices.any { it.device.address == newDevice.address }) {
						updatedDevices
					}
					else (updatedDevices + ScannedBluetoothDevice(device = newDevice, rssi = rssi)).distinct()
				}
			}
		}
		_scope.launch {
			bluetoothEvents.isDiscoveringFlow.collect { isDiscovering ->
				_isScanning.value = isDiscovering
			}
		}
		_scope.launch {
			bluetoothEvents.scanModeFlow.collect { scanMode ->
				_scanMode.value = convertToScanMode(scanMode)
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
									if (!handleDisconnectedDevice(disconnectedDevice.address)) {
										return@launch
									}

									println("$$$$ Device disconnected(locally = true | 5): $disconnectedDevice")

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											device = disconnectedDevice,
											disconnectedLocally = true,
										)
									)
								}
							}

							disconnectedDevice
						}
					}
					_scannedDevices.update { devices ->
						devices.map { scannedDevice ->
							val disconnectedDevice = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)

							if (scannedDevice.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
								launch {
									if (!handleDisconnectedDevice(disconnectedDevice.address)) {
										return@launch
									}

									println("$$$$ Device disconnected(locally = true | 6): $disconnectedDevice")

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											device = disconnectedDevice,
											disconnectedLocally = true,
										)
									)
								}
							}

							scannedDevice.copy(device = disconnectedDevice)
						}
					}

					_connectedDevices.value.also { devices ->
						devices.forEach { device ->
							val disconnectedDevice = device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)

							if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
								launch {
									if (!handleDisconnectedDevice(disconnectedDevice.address)) {
										return@launch
									}

									println("$$$$ Device disconnected(locally = true | 7): $disconnectedDevice")

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											device = disconnectedDevice,
											disconnectedLocally = true,
										)
									)
								}
							}
						}
					}
					_connectedDevices.value = emptyList()
				}
			}
		}
		_scope.launch {
			bluetoothEvents.pairingRequestFlow.collect { event ->
				println("$$$ bluetoothEvents.pairingRequestFlow: $event")

				val targetDeviceAddress = BluetoothDevice.Address(event.androidDevice.address)

				_pairedDevices.update { devices ->
					devices.map { device ->
						if (device.address == targetDeviceAddress) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { scannedDevice ->
						if (scannedDevice.device.address == targetDeviceAddress) {
							scannedDevice.copy(
								device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
							)
						}
						else scannedDevice
					}
				}

				val pairingVariant = when (val pairingVariant = event.pairingVariant) {
					AndroidBluetoothDevice.PAIRING_VARIANT_PIN -> LapisBt.Event.OnPairingRequest.PairingVariant.Pin
					AndroidBluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> LapisBt.Event.OnPairingRequest.PairingVariant.PasskeyConfirmation
					AndroidInternalConstants.PAIRING_VARIANT_CONSENT -> LapisBt.Event.OnPairingRequest.PairingVariant.Consent
					AndroidInternalConstants.PAIRING_VARIANT_DISPLAY_PASSKEY -> LapisBt.Event.OnPairingRequest.PairingVariant.DisplayPasskey
					AndroidInternalConstants.PAIRING_VARIANT_DISPLAY_PIN -> LapisBt.Event.OnPairingRequest.PairingVariant.DisplayPin
					AndroidInternalConstants.PAIRING_VARIANT_OOB_CONSENT -> LapisBt.Event.OnPairingRequest.PairingVariant.OobConsent
					AndroidInternalConstants.PAIRING_VARIANT_PIN_16_DIGITS -> LapisBt.Event.OnPairingRequest.PairingVariant.Pin16Digits
					else -> error("No pairing variant for value: $pairingVariant")
				}

				val initiatedLocally = _pairingsStarted.contains(targetDeviceAddress)

				println("$$$ incomingPairing1: $targetDeviceAddress")
				_incomingPairingRequests.add(targetDeviceAddress)
				println("$$$ incomingPairing2: $targetDeviceAddress")
				_pairingsStarted.remove(targetDeviceAddress)

				_events.emit(
					LapisBt.Event.OnPairingRequest(
						device = getRemoteDeviceInternal(targetDeviceAddress),
						pairingKey = event.pairingKey,
						pairingVariant = pairingVariant,
						initiatedLocally = initiatedLocally,
					)
				)
			}
		}
		_scope.launch {
			combine(
				_bluetoothState.map { it.isOn },
				_isConnectPermissionGranted,
				_isScanPermissionGranted,
			) { isBluetoothOn, isBluetoothConnectPermissionGranted, isBluetoothScanPermissionGranted ->
				if (isBluetoothOn) {
					updateDevices()
				}
				if (isBluetoothConnectPermissionGranted) {
					_bluetoothDeviceName.value = lapisAdapter.name
				}
				if (isBluetoothScanPermissionGranted) {
					_scanMode.value = convertToScanMode(lapisAdapter.scanMode)
				}
			}.collect()
		}
	}

	private fun updateDevices() {
		if (!androidHelper.isBluetoothConnectGranted()) {
			return
		}

		_pairedDevices.update { devices ->
			lapisAdapter.getBondedDevices().orEmpty().map { lapisDevice ->
				val targetDeviceAddress = BluetoothDevice.Address(lapisDevice.address)

				lapisDevice.toModel(
					connectionState = when (targetDeviceAddress) {
						in _clientSocketByAddress -> {
							BluetoothDevice.ConnectionState.Connected
						}
						in devices.map { it.address } -> {
							devices.first { it.address == targetDeviceAddress }.connectionState
						}
						else -> {
							BluetoothDevice.ConnectionState.Disconnected
						}
					},
				)
			}
		}
		_scannedDevices.update { devices ->
			devices.map { scannedDevice ->
				if (_clientSocketByAddress.contains(scannedDevice.device.address)) {
					scannedDevice.copy(
						device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Connected),
					)
				}
				else scannedDevice
			}
		}
		_connectedDevices.update { devices ->
			devices.mapNotNull { device ->
				if (_clientSocketByAddress.contains(device.address)) {
					device
				}
				else {
					println("$$$ update.connected.null: ${device.address}")
					null
				}
			}
		}
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	private suspend fun startBluetoothServerInternal(
		serviceName: String,
		serviceUuid: UUID,
		insecure: Boolean = false,
	): LapisBt.ConnectionResult {
		if (!androidHelper.isBluetoothConnectGranted()) {
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
		_clientSocketByAddress[BluetoothDevice.Address(connectedAndroidDevice.address)] = clientSocket

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		updateDevices()

		_connectedDevices.update { devices ->
			if (devices.none { it.address == connectedDevice.address }) {
				devices + connectedDevice
			}
			else devices
		}

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				device = connectedDevice,
				connectedLocally = false,
			)
		)

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	private suspend fun connectToDeviceInternal(
		deviceAddress: BluetoothDevice.Address,
		serviceUuid: UUID,
		insecure: Boolean = false,
	): LapisBt.ConnectionResult {

		if (!androidHelper.isBluetoothConnectGranted()) {
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
			devices.map { scannedDevice ->
				if (scannedDevice.device.address == deviceAddress) {
					scannedDevice.copy(
						device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Connecting)
					)
				}
				else scannedDevice
			}
		}

		val androidDevice = lapisAdapter.getRemoteDevice(deviceAddress.value)

		val clientSocket = if (insecure) {
			androidDevice.createInsecureRfcommSocketToServiceRecord(serviceUuid)
		}
		else androidDevice.createRfcommSocketToServiceRecord(serviceUuid)

		println("$$$ connectToDeviceInternal.clientSocket: $clientSocket")

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[BluetoothDevice.Address(connectedAndroidDevice.address)] = clientSocket


		println("$$$ start connectToDeviceInternal")
		_skipDisconnectionEventForDevices.add(deviceAddress)
		val isConnectionSuccessFull = clientSocket.tryConnect()
		_skipDisconnectionEventForDevices.remove(deviceAddress)
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
				devices.map { scannedDevice ->
					if (scannedDevice.device.address == deviceAddress) {
						scannedDevice.copy(
							device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						)
					}
					else scannedDevice
				}
			}

			_skipDisconnectionEventForDevices.add(deviceAddress)

			return LapisBt.ConnectionResult.CouldNotConnect
		}

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		updateDevices()

		_connectedDevices.update { devices ->
			if (devices.none { it.address == connectedDevice.address }) {
				devices + connectedDevice
			}
			else devices
		}

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				device = connectedDevice,
				connectedLocally = true,
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

			_clientSocketByAddress[BluetoothDevice.Address(clientSocket.remoteDevice.address)] = clientSocket

			return@withContext clientSocket
		}
	}

//	private suspend fun LapisBluetoothServerSocket.tryAccept(): LapisBluetoothSocket? {
//		return try {
//			suspendCancellableCoroutine<LapisBluetoothSocket?> { continuation ->
//				// This hook triggers INSTANTLY when cancelled, breaking the blocking accept()
//				continuation.invokeOnCancellation {
//					try {
//						this@tryAccept.close()
//					} catch (_: IOException) { }
//				}
//
//				// Move the blocking work to the IO thread pool
//				try {
//					// Since accept() is blocking, we still execute it on IO
//					val socket = this@tryAccept.accept()
//					continuation.resume(socket)
//				} catch (e: IOException) {
//					// If closed via invokeOnCancellation, accept() throws IOException
//					if (continuation.isCancelled) return@suspendCancellableCoroutine
//					continuation.resume(null)
//				}
//			}
//		} catch (e: CancellationException) {
//			null
//		}?.also { clientSocket ->
//			_clientSocketByAddress[BluetoothDevice.Address(clientSocket.remoteDevice.address)] = clientSocket
//		}
//	}

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
						if (e.message == "socket closed") {
							return@withContext false
						}
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

	@Synchronized
	private fun handleDisconnectedDevice(deviceAddress: BluetoothDevice.Address): Boolean {
		val clientSocket = _clientSocketByAddress[deviceAddress]
		try {
			println("$$$ handleDisconnectedDevice.clientSocket: $clientSocket for $deviceAddress")
			clientSocket?.close()
			println("$$$ handleDisconnectedDevice.clientSocket: $clientSocket for $deviceAddress CLOSE")
		}
		catch (e: Exception) {
			println("$$$ handleDisconnectedDevice error: $e")
			Log.e(TAG, "Error closing client socket for $deviceAddress", e)
			return false
		}

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
			devices.map { scannedDevice ->
				if (scannedDevice.device.address == deviceAddress) {
					scannedDevice.copy(
						device = scannedDevice.device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					)
				}
				else scannedDevice
			}
		}
		_connectedDevices.update { devices ->
			println("$$$ handleDisconnection.connected.filter: $deviceAddress")
			devices.filter { it.address != deviceAddress }
		}

		println("$$$ end of handleDisconnectedDevice: $deviceAddress")

		return true
	}

	private fun checkIsNotDispose() {
		check(!_isDisposed) {
			"Can't no longer use this ${LapisBt::class.simpleName} since it was disposed."
		}
	}

	companion object {

		private val TAG = this::class.simpleName!!

		/**
		 * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
		 *
		 *
		 * Alphabetic characters must be uppercase to be valid.
		 *
		 * @param address Bluetooth address as string
		 * @return true if the address is valid, false otherwise
		 */
		fun checkBluetoothAddress(address: String): Boolean {
			return checkBluetoothAddressInternal(address)
		}
	}

	private object AndroidInternalConstants {

		const val PAIRING_VARIANT_CONSENT = 3
		const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
		const val PAIRING_VARIANT_DISPLAY_PIN = 5
		const val PAIRING_VARIANT_OOB_CONSENT = 6
		const val PAIRING_VARIANT_PIN_16_DIGITS = 7

		const val UNBOND_REASON_AUTH_FAILED = 1
		const val UNBOND_REASON_AUTH_REJECTED = 2
		const val UNBOND_REASON_AUTH_CANCELED = 3
		const val UNBOND_REASON_REMOTE_DEVICE_DOWN = 4
		const val UNBOND_REASON_DISCOVERY_IN_PROGRESS = 5
		const val UNBOND_REASON_AUTH_TIMEOUT = 6
		const val UNBOND_REASON_REPEATED_ATTEMPTS = 7
		const val UNBOND_REASON_REMOTE_AUTH_CANCELED = 8
		const val UNBOND_REASON_REMOVED = 9
	}
}
