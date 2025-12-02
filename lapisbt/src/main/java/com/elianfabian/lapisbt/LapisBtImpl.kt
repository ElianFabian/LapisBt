package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.LapisBluetoothSocket
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
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


	// Represents the state of paired and/or connected devices
	private val _devices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val devices = _devices.asStateFlow()

	private val _scannedDevices = MutableSharedFlow<BluetoothDevice>()
	override val scannedDevices = _scannedDevices.asSharedFlow()

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
	override val activeBluetoothServers = _activeBluetoothServersUuids.asStateFlow()

	private var _bluetoothServerSocketByServiceUuid: MutableMap<UUID, LapisBluetoothServerSocket> = ConcurrentHashMap()
	private val _clientSocketByAddress: MutableMap<String, LapisBluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<String, Job> = ConcurrentHashMap()


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
//		if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//			return false
//		}
		if (!androidHelper.isBluetoothScanGranted()) {
			return false
		}

		return lapisAdapter.cancelDiscovery()
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
		if (!lapisAdapter.isEnabled) {
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

			_devices.update { devices ->
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

	// We haven't tested this yet
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
				_devices.update { devices ->
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
				_devices.update { devices ->
					devices.map { existingDevice ->
						if (existingDevice.address == lapisDevice.address) {
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
				val clientSocket = _clientSocketByAddress.remove(disconnectedDevice.address)
				val wasConnected = clientSocket?.isConnected == true

				clientSocket?.close()
				_clientJobByAddress[disconnectedDevice.address]?.cancel()

				if (wasConnected) {
					_events.emit(
						LapisBt.Event.OnDeviceDisconnected(
							disconnectedDevice = _devices.value.find { it.address == disconnectedDevice.address } ?: run {
								return@collect
							},
							manuallyDisconnected = false,
						)
					)
				}

				_devices.update { devices ->
					devices.map { device ->
						if (device.address == disconnectedDevice.address) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}
			}
		}
		_scope.launch {
			bluetoothEvents.deviceNameFlow.collect { newName ->
				_bluetoothDeviceName.value = newName
			}
		}
		_scope.launch {
			bluetoothEvents.deviceUuidsChangeFlow.collect { lapisDevice ->
				_devices.update { devices ->
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
				_scannedDevices.emit(newDevice)
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
					_bluetoothDeviceName.value = lapisAdapter.name
				}
			}.collect()
		}
	}

	private fun updateDevices() {
		if (!canEnableBluetooth) {
			return
		}

		_devices.updateAndGet {
			val connectedDevices = _clientSocketByAddress.keys.map { address ->
				lapisAdapter.getRemoteDevice(address)
			}.map { androidDevice ->
				androidDevice.toModel(
					connectionState = BluetoothDevice.ConnectionState.Connected,
				)
			}

			val pairedDevices = lapisAdapter.getBondedDevices().orEmpty().mapNotNull { androidDevice ->
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

		_events.emit(
			LapisBt.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = false,
			)
		)

		_devices.update { devices ->
			val androidBondedDevices = lapisAdapter.getBondedDevices().orEmpty()
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

		_devices.update { devices ->
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

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

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

		_devices.updateAndGet { devices ->
			val androidBondedDevices = lapisAdapter.getBondedDevices().orEmpty()
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
						connect()
						return@withContext true
					}
					catch (_: IOException) {
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
		require(checkBluetoothAddress(deviceAddress)) {
			"The device address '$deviceAddress' is invalid"
		}
	}

	companion object {
		/**
		 * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
		 *
		 *
		 * Alphabetic characters must be uppercase to be valid.
		 *
		 * @param address Bluetooth address as string
		 * @return true if the address is valid, false otherwise
		 */
		fun checkBluetoothAddress(address: String?): Boolean {
			val addressLength = 17

			if (address == null || address.length != addressLength) {
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
}
