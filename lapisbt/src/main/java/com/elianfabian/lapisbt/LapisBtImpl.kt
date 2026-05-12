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

	private var _isDisposed = false

	private val _isConnectPermissionGranted = MutableStateFlow(androidHelper.isBluetoothConnectGranted())

	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val pairedDevices = _pairedDevices.asStateFlow()

	private val _scannedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val scannedDevices = _scannedDevices.asStateFlow()

	private val _connectedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val connectedDevices = _connectedDevices.asStateFlow()

	private val _events = MutableSharedFlow<LapisBt.Event>()
	override val events = _events.asSharedFlow()

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

	private val _activeBluetoothServersUuids = MutableStateFlow(emptyList<UUID>())
	override val activeBluetoothServersUuids = _activeBluetoothServersUuids.asStateFlow()

	private var _bluetoothServerSocketByServiceUuid: MutableMap<UUID, LapisBluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<String, LapisBluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<String, Job> = ConcurrentHashMap()
	private val _readMutex = KeyedMutex<String>()
	private val _writeMutex = KeyedMutex<String>()

	// This is to avoid duplicate disconnection events
	private val _skipDisconnectionEventForDevices = mutableSetOf<String>()

	// This is to know which device stared the bonding process
	private val _pairingsStarted = mutableSetOf<String>()

	private val _unpairingsStarted = mutableSetOf<String>()

	// This is to check unexpected bonded devices
	// There's more information about this in the code below
	private val _incomingPairingRequests = mutableSetOf<String>()

	init {
		initialize()
	}


	override fun setBluetoothDeviceName(newName: String): Boolean {
		checkIsNotDispose()

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

	override suspend fun connectToDevice(deviceAddress: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
		)
	}

	override suspend fun connectToDeviceWithoutPairing(deviceAddress: String, serviceUuid: UUID): LapisBt.ConnectionResult {
		checkIsNotDispose()

		return connectToDeviceInternal(
			deviceAddress = deviceAddress,
			serviceUuid = serviceUuid,
			insecure = true,
		)
	}

	override suspend fun disconnectFromDevice(deviceAddress: String): Boolean {
		checkIsNotDispose()
		requireValidAddress(deviceAddress)

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

		val disconnectedLocally = try {
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

			handleDisconnectedDevice(deviceAddress)

			val disconnectedDevice = getRemoteDeviceInternal(deviceAddress)

			_events.emit(
				LapisBt.Event.OnDeviceDisconnected(
					disconnectedDevice = disconnectedDevice,
					disconnectedLocally = disconnectedLocally,
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
		checkIsNotDispose()
		requireValidAddress(deviceAddress)

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
		checkIsNotDispose()

		return getRemoteDeviceInternal(deviceAddress)
	}

	private fun getRemoteDeviceInternal(deviceAddress: String): BluetoothDevice {
		checkIsNotDispose()
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

	override fun startDevicePairing(deviceAddress: String): Boolean {
		checkIsNotDispose()

		println("$$$$ LapisBtImpl.startDevicePairing: $deviceAddress")
		val device = lapisAdapter.getRemoteDevice(deviceAddress)

		if (device.createBond()) {
			_scannedDevices.update { devices ->
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(pairingState = BluetoothDevice.PairingState.Pairing)
					}
					else device
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

			_pairingsStarted.add(deviceAddress)

			return true
		}

		return false
	}

	// It seems that unpairing a device will force the disconnection
	@InternalBluetoothReflectionApi
	override fun unpairDevice(deviceAddress: String): Boolean {
		checkIsNotDispose()

		val device = lapisAdapter.getRemoteDevice(deviceAddress)

		if (device.removeBond()) {
			_unpairingsStarted.add(deviceAddress)
			return true
		}

		return false
	}

	@InternalBluetoothReflectionApi
	override fun cancelPairingAttempt(deviceAddress: String): Boolean {
		checkIsNotDispose()

		val device = lapisAdapter.getRemoteDevice(deviceAddress)

		if (device.cancelBondProcess()) {
			_pairingsStarted.remove(deviceAddress)
			return true
		}
		return false
	}

	override suspend fun sendData(deviceAddress: String, action: suspend (stream: OutputStream) -> Unit): Boolean {
		checkIsNotDispose()

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
					if (!_isDisposed) {
						_events.emit(
							LapisBt.Event.OnDeviceDisconnected(
								disconnectedDevice = getRemoteDeviceInternal(deviceAddress),
								disconnectedLocally = false,
							)
						)
					}
					return@withContext false
				}

				return@withContext true
			}
		}
	}

	override suspend fun receiveData(deviceAddress: String, action: suspend (stream: InputStream) -> Unit): Boolean {
		checkIsNotDispose()

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
					if (!_isDisposed) {
						_events.emit(
							LapisBt.Event.OnDeviceDisconnected(
								disconnectedDevice = getRemoteDeviceInternal(deviceAddress),
								disconnectedLocally = false,
							)
						)
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
						// no-op
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
					is LapisBt.Event.OnUnexpectedDevicePaired -> {
						// no-op
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.onActivityResumed.collect {
				updateDevices()

				_isConnectPermissionGranted.value = androidHelper.isBluetoothConnectGranted()

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
			bluetoothEvents.deviceBondStateChangeFlow.collect { lapisDevice ->
				println("$$$ Device bond state changed: $lapisDevice")

				_scannedDevices.update { devices ->
					devices.map { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							existingDevice.copy(
								connectionState = if (_clientSocketByAddress[lapisDevice.address]?.isConnected == true) {
									BluetoothDevice.ConnectionState.Connected
								}
								else BluetoothDevice.ConnectionState.Disconnected,
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
				_pairedDevices.update { devices ->
					val updatedDevices = devices.mapNotNull { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_NONE) {
								return@mapNotNull null
							}
							existingDevice.copy(
								connectionState = if (_clientSocketByAddress[lapisDevice.address]?.isConnected == true) {
									BluetoothDevice.ConnectionState.Connected
								}
								else BluetoothDevice.ConnectionState.Disconnected,
								pairingState = when (lapisDevice.bondState) {
									AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
									AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
									else -> error("Impossible bonding state at the point of execution: ${lapisDevice.bondState}")
								},
							)
						}
						else existingDevice
					}

					if (
						lapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED
						&& lapisDevice.address !in devices.map { it.address }
						&& lapisDevice.address !in _scannedDevices.value.map { it.address }
					) {
						val newDevice = lapisDevice.toModel(
							connectionState = if (_clientSocketByAddress[lapisDevice.address]?.isConnected == true) {
								BluetoothDevice.ConnectionState.Connected
							}
							else BluetoothDevice.ConnectionState.Disconnected,
						)
						println("$$$ newPairedDevice: $lapisDevice")
						updatedDevices + newDevice
					}
					else updatedDevices
				}
				_connectedDevices.update { devices ->
					devices.map { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
							existingDevice.copy(
								connectionState = if (_clientSocketByAddress[lapisDevice.address]?.isConnected == true) {
									BluetoothDevice.ConnectionState.Connected
								}
								else BluetoothDevice.ConnectionState.Disconnected,
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
				if (lapisDevice.bondState == AndroidBluetoothDevice.BOND_BONDED && lapisDevice.address !in _incomingPairingRequests) {
					Log.w(TAG, "Unexpected bonded device ${lapisDevice.address}. This is very likely a Bluetooth stack bug, and the bonded device didn't actually try to pair with this device.")

					_events.emit(
						LapisBt.Event.OnUnexpectedDevicePaired(getRemoteDeviceInternal(lapisDevice.address))
					)
				}
				else if (lapisDevice.bondState != AndroidBluetoothDevice.BOND_BONDING && lapisDevice.address in _incomingPairingRequests) {
					_incomingPairingRequests.remove(lapisDevice.address)
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
					AndroidInternalConstants.UNBOND_REASON_REMOVED -> LapisBt.Event.OnPairingFailed.Reason.Removed
					else -> error("Impossible value for unbond reason: ${unbondReason.reason}")
				}

				val device = getRemoteDeviceInternal(unbondReason.androidDevice.address)

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
				println("$$$ disconnectedDevice(${_clientSocketByAddress[disconnectedAndroidDevice.address]?.isConnected}): $disconnectedAndroidDevice | $_skipDisconnectionEventForDevices")

				if (disconnectedAndroidDevice.address in _skipDisconnectionEventForDevices) {
					_skipDisconnectionEventForDevices.remove(disconnectedAndroidDevice.address)
					return@collect
				}

				// Disconnection events aren't always reliable, they might get fired
				// even when trying to bond, and if no action is taking the dialog
				// times out, and then this event happens.
				// To solve it we just manually check the corresponding client socket.
				if (disconnectedAndroidDevice.address !in _clientSocketByAddress) {
					return@collect
				}

				handleDisconnectedDevice(disconnectedAndroidDevice.address)

				val disconnectedDevice = getRemoteDeviceInternal(disconnectedAndroidDevice.address)

				_events.emit(
					LapisBt.Event.OnDeviceDisconnected(
						disconnectedDevice = disconnectedDevice,
						disconnectedLocally = _unpairingsStarted.contains(disconnectedAndroidDevice.address),
					)
				)

				_unpairingsStarted.remove(disconnectedAndroidDevice.address)
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
									handleDisconnectedDevice(disconnectedDevice.address)

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											disconnectedDevice = disconnectedDevice,
											disconnectedLocally = true,
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
									handleDisconnectedDevice(disconnectedDevice.address)

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											disconnectedDevice = disconnectedDevice,
											disconnectedLocally = true,
										)
									)
								}
							}

							disconnectedDevice
						}
					}
					_connectedDevices.update { devices ->
						devices.map { device ->
							val disconnectedDevice = device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)

							if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
								launch {
									handleDisconnectedDevice(disconnectedDevice.address)

									_events.emit(
										LapisBt.Event.OnDeviceDisconnected(
											disconnectedDevice = disconnectedDevice,
											disconnectedLocally = true,
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
			bluetoothEvents.pairingRequestFlow.collect { event ->

				println("$$$ bluetoothEvents.pairingRequestFlow: $event")

				_pairedDevices.update { devices ->
					devices.map { device ->
						if (device.address == event.androidDevice.address) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}
				_scannedDevices.update { devices ->
					devices.map { device ->
						if (device.address == event.androidDevice.address) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}


				// Maybe pairing variant should be a sealed interface so we can add an unknown value,
				// but for now I think this is useful for development in the very unlikely case
				// we experience a value different from the ones defined here.
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

				_events.emit(
					LapisBt.Event.OnPairingRequest(
						device = getRemoteDeviceInternal(event.androidDevice.address),
						pairingKey = event.pairingKey,
						pairingVariant = pairingVariant,
						initiatedLocally = _pairingsStarted.contains(event.androidDevice.address),
					)
				)

				_incomingPairingRequests.add(event.androidDevice.address)

				_pairingsStarted.remove(event.androidDevice.address)
			}
		}
		_scope.launch {
			combine(
				_bluetoothState.map { it.isOn },
				_isConnectPermissionGranted,
			) { isBluetoothOn, isBluetoothConnectPermissionGranted ->
				if (isBluetoothOn) {
					updateDevices()
				}
				if (isBluetoothConnectPermissionGranted) {
					_bluetoothDeviceName.value = lapisAdapter.name
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
				lapisDevice.toModel(
					connectionState = when (lapisDevice.address) {
						in _clientSocketByAddress -> BluetoothDevice.ConnectionState.Connected
						in devices.map { it.address } -> devices.first { it.address == lapisDevice.address }.connectionState
						else -> BluetoothDevice.ConnectionState.Disconnected
					},
				)
			}
		}
		_scannedDevices.update { devices ->
			devices.map { device ->
				if (_clientSocketByAddress.contains(device.address)) {
					device.copy(
						connectionState = BluetoothDevice.ConnectionState.Connected,
					)
				}
				else device
			}
		}
		_connectedDevices.update { devices ->
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
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		updateDevices()

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				connectedLocally = false,
			)
		)

		return LapisBt.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	private suspend fun connectToDeviceInternal(
		deviceAddress: String,
		serviceUuid: UUID,
		insecure: Boolean = false,
	): LapisBt.ConnectionResult {
		requireValidAddress(deviceAddress)

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
				devices.map { device ->
					if (device.address == deviceAddress) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}

			_skipDisconnectionEventForDevices.add(deviceAddress)

			return LapisBt.ConnectionResult.CouldNotConnect
		}

		val connectedDevice = connectedAndroidDevice.toModel(
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		updateDevices()

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
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

	private fun handleDisconnectedDevice(deviceAddress: String) {
		val clientSocket = _clientSocketByAddress[deviceAddress]
		clientSocket?.close()
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
		_connectedDevices.update { devices ->
			devices.filter { it.address != deviceAddress }
		}
	}

	private fun requireValidAddress(deviceAddress: String) {
		require(LapisBt.checkBluetoothAddress(deviceAddress)) {
			"The device address '$deviceAddress' is invalid"
		}
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
			val addressLength = 17

			if (address.length != addressLength) {
				return false
			}
			for (i in 0..<addressLength) {
				val c = address[i]
				when (i % 3) {
					0, 1 -> {
						if ((c in '0'..'9') || (c in 'A'..'F')) {
							// hex character, OK
							break
						}
						return false
					}
					2 -> {
						if (c == ':') {
							break // OK
						}
						return false
					}
				}
			}
			return true
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
