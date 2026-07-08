package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogConfig
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.debug
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.error
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.info
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.verbose
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.warning
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.util.AndroidInternalConstants
import com.elianfabian.lapisbt.util.KeyedMutex
import com.elianfabian.lapisbt.util.checkBluetoothAddressInternal
import com.elianfabian.lapisbt.util.convertToScanMode
import com.elianfabian.lapisbt.util.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
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
import kotlin.time.Duration

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

	private val _bluetoothServerSocketByServiceUuid: MutableMap<UUID, LapisBluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<BluetoothDevice.Address, LapisBluetoothSocket> = ConcurrentHashMap()
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
		checkIsNotDisposed()

		logger.debug(TAG) {
			"setBluetoothDeviceName('$newName'): Setting name..."
		}

		if (!androidHelper.isBluetoothConnectGranted()) {
			return false
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

	override fun startScan(): LapisBt.ScanResult {
		checkIsNotDisposed()

		logger.info(TAG) { "startScan(): Starting discovery..." }

		// Hardware and State Checks
		if (!androidHelper.isBluetoothClassicSupported()) {
			return LapisBt.ScanResult.BluetoothNotSupported
		}
		if (!lapisAdapter.isEnabled) {
			return LapisBt.ScanResult.BluetoothDisabled
		}
		if (lapisAdapter.isDiscovering) {
			return LapisBt.ScanResult.ScanAlreadyInProgress
		}

		val apiLevel = androidHelper.getApiLevel()

		when {
			apiLevel >= 31 -> {
				// Because your manifest uses 'neverForLocation' and maxSdkVersion="30" for location,
				// Android 12+ DOES NOT require any location permission for background scans.
				// Just BLUETOOTH_SCAN is enough!
				if (!androidHelper.isBluetoothScanGranted()) {
					return LapisBt.ScanResult.MissingBluetoothScanPermission
				}
			}
			apiLevel in 29..30 -> {
				if (!androidHelper.isAccessFineLocationGranted()) {
					return LapisBt.ScanResult.MissingLocationPermission
				}
			}
			apiLevel in 23..28 -> {
				if (!androidHelper.isAccessCoarseLocationGranted() && !androidHelper.isAccessFineLocationGranted()) {
					return LapisBt.ScanResult.MissingLocationPermission
				}
			}
		}

		try {
			if (!lapisAdapter.startDiscovery()) {
				// On most devices location is required to scan, but others like Realme 6 API 30 don't require it
				if (apiLevel in 23..30 && !androidHelper.isLocationEnabled()) {
					// It would be cool to know if the current device needs location to scan or not,
					// but for now we'll just keep it like this
					return LapisBt.ScanResult.LocationDisabled
				}
				return LapisBt.ScanResult.UnknownError
			}
		}
		catch (e: SecurityException) {
			logger.error(TAG, e) { "startScan(): SecurityException triggered during startDiscovery." }

			return LapisBt.ScanResult.UnknownError
		}

		updateDevices()

		_isScanning.value = true

		return LapisBt.ScanResult.Success
	}

	override fun stopScan(): Boolean {
		checkIsNotDisposed()

		logger.info(TAG) { "stopScan(): Stopping discovery..." }

		// Fast-path: If Bluetooth is disabled or not supported, it's already stopped.
		if (!androidHelper.isBluetoothClassicSupported() || !lapisAdapter.isEnabled) {
			return false
		}

		// Optimization: If it's not even discovering, consider it a success.
		if (!lapisAdapter.isDiscovering) {
			return true
		}

		// Strict Permission Check (Only required for API 31+)
		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		// Execution with defensive try-catch
		try {
			if (lapisAdapter.cancelDiscovery()) {
				_isScanning.value = false
				return true
			}
			return false
		}
		catch (e: SecurityException) {
			logger.error(TAG, e) { "stopScan(): SecurityException triggered during cancelDiscovery." }
			return false
		}
	}

	override fun clearScannedDevices() {
		checkIsNotDisposed()

		_scannedDevices.value = emptyList()
	}

	override suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDisposed()

		logger.info(TAG) {
			"startBluetoothServer('$serviceName', $serviceUuid): Starting..."
		}

		return startBluetoothServerInternal(
			serviceName = serviceName,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDisposed()

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
		checkIsNotDisposed()

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

	override suspend fun connectToDevice(
		deviceAddress: BluetoothDevice.Address,
		serviceUuid: UUID,
		maxRetries: Int,
		retryDelay: Duration,
	): LapisBt.ConnectionResult {
		checkIsNotDisposed()

		logger.info(TAG) {
			"connectToDevice($deviceAddress, $serviceUuid): Connecting..."
		}

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
			maxRetries = maxRetries,
			retryDelay = retryDelay,
		)
	}

	override suspend fun connectToDeviceWithoutPairing(
		deviceAddress: BluetoothDevice.Address,
		serviceUuid: UUID,
		maxRetries: Int,
		retryDelay: Duration,
	): LapisBt.ConnectionResult {
		checkIsNotDisposed()

		logger.info(TAG) {
			"connectToDeviceWithoutPairing($deviceAddress, $serviceUuid): Connecting insecurely..."
		}

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
			insecure = true,
			maxRetries = maxRetries,
			retryDelay = retryDelay,
		)
	}

	override suspend fun disconnectFromDevice(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDisposed()

		if (!androidHelper.isBluetoothConnectGranted()) {
			return false
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
		if (!handleDisconnectedDevice(deviceAddress, clientSocket)) {
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
		checkIsNotDisposed()

		logger.debug(TAG) {
			"Cancelling connection attempt to $deviceAddress..."
		}

		if (!androidHelper.isBluetoothConnectGranted()) {
			return false
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

			updateDeviceInAllLists(deviceAddress) { device ->
				device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
			}
		}
		catch (_: IOException) {
			return false
		}

		return true
	}

	override fun getRemoteDevice(deviceAddress: BluetoothDevice.Address): BluetoothDevice {
		checkIsNotDisposed()

		return getRemoteDeviceInternal(deviceAddress)
	}

	private fun getRemoteDeviceInternal(deviceAddress: BluetoothDevice.Address): BluetoothDevice {
		checkIsNotDisposed()

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
		checkIsNotDisposed()

		logger.debug(TAG) {
			"startDevicePairing($deviceAddress): Starting process..."
		}
		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.createBond()) {
			updateDeviceInAllLists(deviceAddress) { device ->
				device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
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
		checkIsNotDisposed()

		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.removeBond()) {
			_unpairingsStarted.add(deviceAddress)
			return true
		}

		return false
	}

	@InternalBluetoothReflectionApi
	override fun cancelPairingAttempt(deviceAddress: BluetoothDevice.Address): Boolean {
		checkIsNotDisposed()

		val device = lapisAdapter.getRemoteDevice(deviceAddress.value)

		if (device.cancelBondProcess()) {
			_pairingsStarted.remove(deviceAddress)
			return true
		}
		return false
	}

	override suspend fun sendData(deviceAddress: BluetoothDevice.Address, action: suspend (stream: OutputStream) -> Unit): Boolean {
		checkIsNotDisposed()

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
						if (!handleDisconnectedDevice(deviceAddress, clientSocket)) {
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
		checkIsNotDisposed()

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
						"receiveData error($deviceAddress, ${clientSocket.hashCode()})"
					}
					if (_isDisposed) {
						return@withContext false
					}
					logger.info(TAG) {
						"Device disconnected during receiveData: ${getRemoteDeviceInternal(deviceAddress)}"
					}
					_skipDisconnectionEventForDevices.add(deviceAddress)
					if (!handleDisconnectedDevice(deviceAddress, clientSocket)) {
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

					return@withContext false
				}

				return@withContext true
			}
		}
	}

	// TODO: we should test this function
	override suspend fun getUuidsWithSdp(deviceAddress: BluetoothDevice.Address): List<UUID>? = coroutineScope {
		logger.debug(TAG) { "getUuidsWithSdp($deviceAddress): Fetching UUIDs..." }
		val lapisDevice = lapisAdapter.getRemoteDevice(deviceAddress.value)

		val sdpFetchTask = async {
			bluetoothEvents.deviceUuidsChangedFlow.first {
				it.androidDevice.address == deviceAddress.value
			}
		}

		val initiated = lapisDevice.fetchUuidsWithSdp()
		if (!initiated) {
			logger.warning(TAG) {
				"getUuidsWithSdp($deviceAddress): Failed to initiate SDP fetch."
			}
			sdpFetchTask.cancel()
			return@coroutineScope null
		}

		return@coroutineScope sdpFetchTask.await().uuids
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
				updateDeviceInAllLists(BluetoothDevice.Address(lapisDevice.address)) { device ->
					device.copy(alias = lapisDevice.alias)
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

					if (bondStateChangeLapisDevice.bondState == AndroidBluetoothDevice.BOND_NONE && !_unpairingsStarted.contains(targetDeviceAddress)) {
						logger.warning(TAG) {
							"Unexpected bonded device ${bondStateChangeLapisDevice.address} unbonded."
						}
					}
					if (bondStateChangeLapisDevice.bondState == AndroidBluetoothDevice.BOND_NONE && _unpairingsStarted.contains(targetDeviceAddress)) {
						_unpairingsStarted.remove(targetDeviceAddress)
					}

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
						//_unpairingsStarted.remove(BluetoothDevice.Address(unbondReason.androidDevice.address))
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
			bluetoothEvents.deviceUuidsChangedFlow.collect { event ->
				val targetDeviceAddress = BluetoothDevice.Address(event.androidDevice.address)

				if (event.isTimeout) {
					logger.warning(TAG) { "SDP query timed out for $targetDeviceAddress. Retaining cached UUIDs." }
					return@collect
				}

				val lapisDevice = event.androidDevice

				updateDeviceInAllLists(targetDeviceAddress) { device ->
					device.copy(uuids = lapisDevice.uuids)
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

				updateDeviceInAllLists(targetDeviceAddress) { device ->
					// A pairing request will force the device to be disconnected
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
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
					connectionState = when {
						_clientSocketByAddress[targetDeviceAddress]?.isConnected == true -> {
							BluetoothDevice.ConnectionState.Connected
						}
						targetDeviceAddress in devices.map { it.address } -> {
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
				if (_clientSocketByAddress[scannedDevice.device.address]?.isConnected == true) {
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
		if (!androidHelper.isBluetoothClassicSupported()) {
			return LapisBt.ConnectionResult.BluetoothNotSupported
		}
		if (!androidHelper.isBluetoothConnectGranted()) {
			return LapisBt.ConnectionResult.MissingPermission
		}
		if (!lapisAdapter.isEnabled) {
			return LapisBt.ConnectionResult.BluetoothDisabled
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
		maxRetries: Int,
		retryDelay: Duration,
	): LapisBt.ConnectionResult {
		if (!androidHelper.isBluetoothClassicSupported()) {
			return LapisBt.ConnectionResult.BluetoothNotSupported
		}
		if (!androidHelper.isBluetoothConnectGranted()) {
			return LapisBt.ConnectionResult.MissingPermission
		}
		if (!lapisAdapter.isEnabled) {
			return LapisBt.ConnectionResult.BluetoothDisabled
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
			"connectToDeviceInternal($deviceAddress, ${clientSocket.hashCode()}): socket created"
		}

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[BluetoothDevice.Address(connectedAndroidDevice.address)] = clientSocket

		logger.debug(TAG) {
			"connectToDeviceInternal($deviceAddress): attempting to connect socket..."
		}
		_skipDisconnectionEventForDevices.add(deviceAddress)
		val isConnectionSuccessFull = try {
			clientSocket.tryConnect(
				maxRetries = maxRetries,
				retryDelay = retryDelay,
			)
		}
		catch (e: Exception) {
			logger.error(TAG, e) { "connectToDeviceInternal($deviceAddress): Unexpected crash during connection loop" }
			false
		}
		finally {
			_skipDisconnectionEventForDevices.remove(deviceAddress)
		}
		logger.info(TAG) {
			"connectToDeviceInternal($deviceAddress): connection attempt result: success=$isConnectionSuccessFull"
		}

		if (!isConnectionSuccessFull) {
			_bluetoothServerSocketByServiceUuid.remove(serviceUuid)

			updateDeviceInAllLists(deviceAddress) { device ->
				device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
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

			return@withContext clientSocket
		}
	}

	private suspend fun LapisBluetoothSocket.tryConnect(
		maxRetries: Int,
		retryDelay: Duration,
	): Boolean {
		return withContext(Dispatchers.IO) {
			val cancelHandler = coroutineContext.job.invokeOnCompletion { throwable ->
				if (throwable is CancellationException) {
					runCatching { this@tryConnect.close() }
				}
			}

			try {
				val totalAttempts = maxRetries + 1

				for (attempt in 1..totalAttempts) {
					ensureActive()

					try {
						if (attempt > 1) {
							logger.debug(TAG) {
								"tryConnect: Attempt $attempt/$totalAttempts, retrying socket connection..."
							}
							// Giving the Android Bluetooth stack some time to recover before retrying
							delay(retryDelay)
							ensureActive()
						}

						// If device is not paired it will show a pop-up dialog to pair it (if the connection is done securely)
						connect()
						return@withContext true
					}
					catch (e: IOException) {
						val message = e.message.orEmpty()
						logger.error(TAG, e) { "tryConnect: Attempt $attempt/$totalAttempts failed. Message: $message" }

						val isTimeoutError = message.contains("read failed, socket might closed or timeout")
						val hasRemainingRetries = attempt < totalAttempts

						// We only retry if it's the specific timeout error, and we haven't exhausted our attempts
						if (!isTimeoutError || !hasRemainingRetries) {
							if (message == "socket closed") {
								return@withContext false
							}
							break
						}
					}
				}

				// If we reached this point, all allowed attempts failed
				close()
				logger.warning(TAG) { "tryConnect failed after $totalAttempts attempts" }
				return@withContext false
			}
			finally {
				cancelHandler.dispose()
			}
		}
	}

	@Synchronized
	private fun handleDisconnectedDevice(
		deviceAddress: BluetoothDevice.Address,
		socket: LapisBluetoothSocket? = null,
	): Boolean {
		val callerMethodName = Throwable().stackTrace.map { it.methodName }.takeIf { it.isNotEmpty() } ?: "Unknown"
		logger.debug(TAG) { "handleDisconnectedDevice called by: $callerMethodName" }

		if (socket != null) {
			val currentSocket = _clientSocketByAddress[deviceAddress]
			// We check if the socket that triggered the disconnection is the same one currently stored in the map.
			// This prevents a race condition where a reconnection established a NEW socket, but the cleanup
			// loop of the PREVIOUS connection finally catches an exception and tries to clear the map,
			// which would accidentally remove and close the new, healthy socket.
			if (currentSocket != null && currentSocket !== socket) {
				logger.debug(TAG) {
					"handleDisconnectedDevice($deviceAddress): Ignoring disconnection cleanup for mismatched socket (current: ${currentSocket.hashCode()}, provided: ${socket.hashCode()})"
				}
				return false
			}
		}

		val clientSocket = _clientSocketByAddress.remove(deviceAddress)
		try {
			logger.debug(TAG) {
				"handleDisconnectedDevice($deviceAddress): closing socket and cleaning up resources..."
			}
			clientSocket?.close()
			logger.debug(TAG) {
				"handleDisconnectedDevice($deviceAddress, ${clientSocket.hashCode()}): socket closed and resources cleaned up"
			}
		}
		catch (e: Exception) {
			logger.error(TAG, e) {
				"Error closing client socket for $deviceAddress"
			}
			return false
		}

		updateDeviceInAllLists(deviceAddress) { device ->
			device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
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

	private fun checkIsNotDisposed() {
		check(!_isDisposed) {
			"Can't no longer use this ${LapisBt::class.simpleName} since it was disposed."
		}
	}

	// TODO: add unit tests for this, now if we empty the function body the tests still pass
	private fun updateDeviceInAllLists(
		address: BluetoothDevice.Address,
		transform: (BluetoothDevice) -> BluetoothDevice,
	) {
		_pairedDevices.update { devices ->
			devices.map { device -> if (device.address == address) transform(device) else device }
		}
		_scannedDevices.update { scannedDevices ->
			scannedDevices.map { scannedDevice ->
				if (scannedDevice.device.address == address) {
					scannedDevice.copy(device = transform(scannedDevice.device))
				}
				else scannedDevice
			}
		}
		_connectedDevices.update { devices ->
			devices.map { device -> if (device.address == address) transform(device) else device }
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
}
