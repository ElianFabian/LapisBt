package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.common.util.KeyedMutex
import com.elianfabian.lapisbt.common.util.LapisLogConfig
import com.elianfabian.lapisbt.common.util.LapisLogger
import com.elianfabian.lapisbt.common.util.LapisLogger.Companion.debug
import com.elianfabian.lapisbt.common.util.LapisLogger.Companion.error
import com.elianfabian.lapisbt.common.util.LapisLogger.Companion.info
import com.elianfabian.lapisbt.common.util.LapisLogger.Companion.verbose
import com.elianfabian.lapisbt.common.util.LapisLogger.Companion.warning
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

internal class LapisBtImpl(
	private val lapisAdapter: LapisBluetoothAdapter,
	private val androidHelper: AndroidHelper,
	private val bluetoothEvents: LapisBluetoothEvents,
	private val logger: LapisLogger,
) : LapisBt {

	override val logConfig: LapisLogConfig get() = logger

	private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	@Volatile
	private var _isDisposed = false

	private val _isConnectPermissionGranted = MutableStateFlow(androidHelper.isBluetoothConnectGranted())
	private val _isScanPermissionGranted = MutableStateFlow(androidHelper.isBluetoothScanGranted())

	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val pairedDevices = _pairedDevices.asStateFlow()

	private val _scannedDevices = MutableStateFlow(emptyList<ScannedBluetoothDevice>())
	override val scannedDevices = _scannedDevices.asStateFlow()

	private val _connectedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val connectedDevices = _connectedDevices.asStateFlow()

	private val _events = MutableSharedFlow<LapisBt.Event>(extraBufferCapacity = Int.MAX_VALUE)
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
//		_scope.launch {
//			_scannedDevices.collect { scannedDevices ->
//				logger.verbose(TAG, "scannedDevice: ${scannedDevices.groupingBy { it.device.address.value }.eachCount()}")
//			}
//		}
//		_scope.launch {
//			_connectedDevices.collect { scannedDevices ->
//				logger.verbose(TAG, "connectedDevices: ${scannedDevices.groupingBy { it.address.value }.eachCount()}")
//			}
//		}
//		_scope.launch {
//			_pairedDevices.collect { scannedDevices ->
//				logger.verbose(TAG, "pairedDevices: ${scannedDevices.groupingBy { it.address.value }.eachCount()}")
//			}
//		}
		initialize()
	}


	override fun setBluetoothDeviceName(newName: String): Boolean {
		checkIsNotDispose()

		logger.debug(TAG) {
			"setBluetoothDeviceName('$newName'): Setting name..."
		}

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

		logger.info(TAG) {
			"startScan(): Starting discovery..."
		}

		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		updateDevices()

		return lapisAdapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		checkIsNotDispose()

		logger.info(TAG) {
			"stopScan(): Stopping discovery..."
		}

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

		logger.info(TAG) {
			"startBluetoothServer('$serviceName', $serviceUuid): Starting..."
		}

		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		logger.info(TAG) {
			"startBluetoothServerWithoutPairing('$serviceName', $serviceUuid): Starting insecure server..."
		}

		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override fun stopBluetoothServer(serviceUuid: UUID) {
		checkIsNotDispose()

		logger.info(TAG) {
			"stopBluetoothServer($serviceUuid): Stopping..."
		}

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

		logger.info(TAG) {
			"connectToDevice($deviceAddress, $serviceUuid): Connecting..."
		}

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun connectToDeviceWithoutPairing(deviceAddress: BluetoothDevice.Address, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		logger.info(TAG) {
			"connectToDeviceWithoutPairing($deviceAddress, $serviceUuid): Connecting insecurely..."
		}

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
		logger.debug(TAG) {
			"disconnectFromDevice($deviceAddress): Disconnecting..."
		}
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
			logger.debug(TAG) {
				"disconnectFromDevice($deviceAddress): verifying connection by writing dummy byte..."
			}
			withContext(Dispatchers.IO) {
				clientSocket.outputStream.write(0)
			}
			logger.debug(TAG) {
				"disconnectFromDevice($deviceAddress): connection verification successful"
			}
			true
		}
		catch (e: IOException) {
			logger.debug(TAG) {
				"disconnectFromDevice($deviceAddress): verification failed (expected if already disconnected): ${e.message}"
			}
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

		logger.info(TAG) {
			"Device $deviceAddress disconnected (locally initiated: $disconnectedLocally)"
		}

		_events.tryEmit(
			LapisBt.Event.OnDeviceDisconnected(
				device = disconnectedDevice,
				disconnectedLocally = disconnectedLocally,
			)
		)

		_skipDisconnectionEventForDevices.remove(deviceAddress)

		logger.debug(TAG) {
			"disconnectFromDevice($deviceAddress) completed"
		}

		return true
	}

	override suspend fun cancelConnectionAttempt(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDispose()

		logger.debug(TAG) {
			"Cancelling connection attempt to $deviceAddress..."
		}

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

		logger.debug(TAG) {
			"startDevicePairing($deviceAddress): Starting process..."
		}
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

			logger.debug(TAG) {
				"Pairing process initiated for $deviceAddress"
			}
			_pairingsStarted.add(deviceAddress)

			return true
		}

		return false
	}

	@InternalBluetoothReflectionApi
	override fun unpairDevice(deviceAddress: BluetoothDevice.Address): Boolean {
		logger.debug(TAG) {
			"unpairDevice($deviceAddress): Unpairing..."
		}
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
					logger.error(TAG, e) {
						"sendData error"
					}
					if (!_isDisposed) {
						logger.info(TAG) {
							"Device disconnected during sendData: ${getRemoteDeviceInternal(deviceAddress)}"
						}
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

		logger.verbose(TAG) {
			"receiveData($deviceAddress) = $clientSocket | isConnected = ${clientSocket?.isConnected}"
		}

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
					logger.error(TAG, e) {
						"receiveData error($deviceAddress)"
					}
					if (!_isDisposed) {
						logger.info(TAG) {
							"Device disconnected during receiveData: ${getRemoteDeviceInternal(deviceAddress)}"
						}
						_skipDisconnectionEventForDevices.add(deviceAddress)
						if (!handleDisconnectedDevice(deviceAddress)) {
							_skipDisconnectionEventForDevices.remove(deviceAddress)
							return@withContext false
						}
						logger.debug(TAG) {
							"receiveData($deviceAddress): emitting disconnection event"
						}
						_scope.launch {
							try {
								_events.emit(
									LapisBt.Event.OnDeviceDisconnected(
										device = getRemoteDeviceInternal(deviceAddress),
										disconnectedLocally = _unpairingsStarted.contains(deviceAddress),
									)
								)
							}
							catch (e: CancellationException) {
								logger.debug(TAG) {
									"receiveData($deviceAddress): disconnection event emission cancelled: ${e.message}"
								}
								throw e
							}
							catch (e: Throwable) {
								logger.error(TAG, e) {
									"receiveData($deviceAddress): error emitting disconnection event"
								}
							}
							logger.debug(TAG) {
								"receiveData($deviceAddress): disconnection event emitted"
							}
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

	override fun fetchUuidsWithSdp(deviceAddress: BluetoothDevice.Address): Boolean {
		logger.debug(TAG) {
			"fetchUuidsWithSdp($deviceAddress): Fetching UUIDs..."
		}
		val lapisDevice = lapisAdapter.getRemoteDevice(deviceAddress.value)
		return lapisDevice.fetchUuidsWithSdp()
	}

	// TODO: check if everything in this class was garbage-collected
	override fun dispose() {
		if (_isDisposed) {
			return
		}

		_isDisposed = true

		logger.info(TAG) {
			"dispose(): Disposing LapisBt instance..."
		}

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
				logger.info(TAG) {
					"Bluetooth adapter state changed: $state"
				}
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
					logger.verbose(TAG) {
						"Bond state changed for ${bondStateChangeLapisDevice.address}: ${bondStateChangeLapisDevice.bondState}"
					}

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
						logger.verbose(TAG) {
							"bondStateChange.existing: $existingDevice"
						}
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
					logger.verbose(TAG) {
						"Bonding state tracking - pending pairings: $_pairingsStarted, incoming requests: $_incomingPairingRequests"
					}
					if (
						bondStateChangeLapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED
						&& targetDeviceAddress !in _incomingPairingRequests
						&& targetDeviceAddress !in _pairingsStarted
					) {
						logger.warning(TAG) {
							"Unexpected bonded device ${bondStateChangeLapisDevice.address}. This is very likely a Bluetooth stack bug, and the bonded device didn't actually try to pair with this device."
						}

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

				val device = getRemoteDeviceInternal(BluetoothDevice.Address(unbondReason.androidDevice.address))

				logger.info(TAG) {
					"Device ${device.address} unpaired. Reason: $reason"
				}

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

				logger.verbose(TAG) {
					"Disconnection event received for ${targetDeviceAddress}. skipEvents=$_skipDisconnectionEventForDevices"
				}

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

				logger.info(TAG) {
					"Device $targetDeviceAddress disconnected (locally initiated: ${_unpairingsStarted.contains(targetDeviceAddress)})"
				}

				_events.emit(
					LapisBt.Event.OnDeviceDisconnected(
						device = disconnectedDevice,
						disconnectedLocally = _unpairingsStarted.contains(targetDeviceAddress),
					)
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
				logger.verbose(TAG) {
					"Device found: ${lapisDevice.address} (RSSI: $rssi)"
				}
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
				logger.info(TAG) {
					"Bluetooth discovery state changed: isScanning=$isDiscovering"
				}
				_isScanning.value = isDiscovering
			}
		}
		_scope.launch {
			bluetoothEvents.scanModeFlow.collect { scanMode ->
				logger.debug(TAG) {
					"Bluetooth scan mode changed: $scanMode"
				}
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

									logger.info(TAG) {
										"Device ${disconnectedDevice.address} disconnected because Bluetooth was turned off"
									}

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

									logger.info(TAG) {
										"Scanned device ${disconnectedDevice.address} disconnected because Bluetooth was turned off"
									}

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

									logger.info(TAG) {
										"Connected device ${disconnectedDevice.address} disconnected because Bluetooth was turned off"
									}

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
				logger.debug(TAG) {
					"Received pairing request: $event"
				}

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

				logger.debug(TAG) {
					"Processing pairing request from $targetDeviceAddress (locally initiated: $initiatedLocally)"
				}
				_incomingPairingRequests.add(targetDeviceAddress)
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
					logger.verbose(TAG) {
						"updateDevices: removing ${device.address} from connected list as it's no longer connected"
					}
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

		logger.debug(TAG) {
			"connectToDeviceInternal($deviceAddress): socket created"
		}

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[BluetoothDevice.Address(connectedAndroidDevice.address)] = clientSocket


		logger.debug(TAG) {
			"connectToDeviceInternal($deviceAddress): attempting to connect socket..."
		}
		_skipDisconnectionEventForDevices.add(deviceAddress)
		val isConnectionSuccessFull = clientSocket.tryConnect()
		_skipDisconnectionEventForDevices.remove(deviceAddress)
		logger.info(TAG) {
			"connectToDeviceInternal($deviceAddress): connection attempt result: success=$isConnectionSuccessFull"
		}

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
					logger.debug(TAG) {
						"tryAccept: closing server socket because the operation was cancelled"
					}
					this@tryAccept.close()
				}
				catch (e: IOException) {
					logger.error(TAG, e) {
						"tryAccept: error closing server socket"
					}
				}
			}

			val clientSocket = try {
				ensureActive()
				// If device is not paired it will show a pop-up dialog to pair it
				accept()
			}
			catch (e: IOException) {
				logger.error(TAG, e) {
					"tryAccept: accept failed"
				}
				null
			}
			finally {
				cancelHandler.dispose()
			}

			if (clientSocket == null) {
				close()
				return@withContext null
			}

			val address = BluetoothDevice.Address(clientSocket.remoteDevice.address)
			logger.info(TAG) {
				"tryAccept: accepted incoming connection from $address"
			}
			_clientSocketByAddress[address] = clientSocket

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
						logger.debug(TAG) {
							"tryConnect: first attempt failed (timeout), retrying socket connection..."
						}
						connect()
						return@withContext true
					}
					catch (e: IOException) {
						logger.error(TAG, e) {
							"tryConnect retry failed"
						}
						if (e.message == "socket closed") {
							return@withContext false
						}
					}
				}
				close()
				logger.error(TAG, e) {
					"tryConnect failed"
				}
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
			logger.debug(TAG) {
				"handleDisconnectedDevice($deviceAddress): closing socket and cleaning up resources..."
			}
			clientSocket?.close()
			logger.debug(TAG) {
				"handleDisconnectedDevice($deviceAddress): socket closed and resources cleaned up"
			}
		}
		catch (e: Exception) {
			logger.error(TAG, e) {
				"Error closing client socket for $deviceAddress"
			}
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
			logger.verbose(TAG) {
				"handleDisconnectedDevice($deviceAddress): filtering out device from connected list"
			}
			devices.filter { it.address != deviceAddress }
		}

		logger.debug(TAG) {
			"handleDisconnectedDevice($deviceAddress) completed"
		}

		return true
	}

	private fun checkIsNotDispose() {
		check(!_isDisposed) {
			"Can't no longer use this ${LapisBt::class.simpleName} since it was disposed."
		}
	}

	companion object {

		private val TAG = LapisBtImpl::class.simpleName!!

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
