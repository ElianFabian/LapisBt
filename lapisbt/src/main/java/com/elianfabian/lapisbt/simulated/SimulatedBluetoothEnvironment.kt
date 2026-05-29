package com.elianfabian.lapisbt.simulated

import android.content.Context
import com.elianfabian.lapisbt.LapisBtImpl
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.util.BidirectionalStreamPipe
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

public class SimulatedBluetoothEnvironment internal constructor(
	seed: Long = 1L,
	private val context: Context? = null,
	public val globalConfig: SimulatedBluetoothConfiguration = SimulatedBluetoothConfiguration(),
) {
	/**
	 * A [CoroutineScope] tied to the lifecycle of this environment.
	 * It is cancelled when [dispose] is called.
	 */
	public val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	private val _random = Random(seed)
	private val _devices = ConcurrentHashMap<String, SimulatedBluetoothDeviceEntry>()
	private val _activeServers = ConcurrentHashMap<String, MutableMap<UUID, ServerEntry>>()
	private val _bondingRegistry = ConcurrentHashMap<String, MutableSet<String>>()
	private val _activeConnections = ConcurrentHashMap<Pair<String, String>, MutableList<SimulatedLapisBluetoothSocket>>()

	private var _isDisposed = false

	internal data class SimulatedBluetoothDeviceEntry(
		val device: SimulatedBluetoothDevice,
		val adapter: SimulatedLapisBluetoothAdapter,
		val events: SimulatedLapisBluetoothEvents,
		val config: SimulatedBluetoothConfiguration,
	)

	internal data class ServerEntry(
		val socket: SimulatedLapisBluetoothServerSocket,
		val isSecure: Boolean,
	)

	public fun createDevice(
		address: String = generateAddress(),
		name: String? = "Device ${address.takeLast(5)}",
		config: SimulatedBluetoothConfiguration = globalConfig.copy(),
		useRealDeviceState: Boolean = true,
	): SimulatedBluetoothDevice {
		check(!_isDisposed) {
			"Environment is disposed"
		}

		val context = if (useRealDeviceState) {
			context
		}
		else null

		val events = SimulatedLapisBluetoothEvents(context)
		val adapter = SimulatedLapisBluetoothAdapter(
			bluetoothEvents = events,
			config = config,
			environment = this,
			context = context,
		)
		adapter.address = address
		if (name != null) {
			adapter.setName(name)
		}

		val simulatedAndroidHelper = SimulatedAndroidHelper(
			config = config,
			context = context,
		)

		val lapisBt = LapisBtImpl(
			lapisAdapter = adapter,
			androidHelper = simulatedAndroidHelper,
			bluetoothEvents = events,
		)

		val device = SimulatedBluetoothDevice(
			address = BluetoothDevice.Address(address),
			lapisBt = lapisBt,
			config = config,
			events = events,
			environment = this,
		)
		val entry = SimulatedBluetoothDeviceEntry(
			device = device,
			adapter = adapter,
			events = events,
			config = config,
		)
		_devices[address] = entry
		return device
	}

	private fun generateAddress(): String {
		return List(6) { _random.nextInt(0, 256) }.joinToString(":") { "%02X".format(it) }
	}

	internal fun getScannableDevices(requesterAddress: String): List<LapisBluetoothDevice> {
		return _devices.values
			.filter { it.device.address.value != requesterAddress }
			.map { entry ->
				SimulatedLapisBluetoothDevice(
					address = entry.device.address.value,
					name = entry.device.name,
					bluetoothEvents = entry.events,
					environment = this,
					requesterAddress = requesterAddress,
				)
			}
	}

	internal fun registerServer(deviceAddress: String, uuid: UUID, socket: SimulatedLapisBluetoothServerSocket, isSecure: Boolean) {
		_activeServers.getOrPut(deviceAddress) { ConcurrentHashMap() }[uuid] = ServerEntry(socket, isSecure)
	}

	public fun getDeviceConfig(address: String): SimulatedBluetoothConfiguration? {
		return _devices[address]?.config
	}

	internal fun getDeviceEvents(address: String): SimulatedLapisBluetoothEvents? {
		return _devices[address]?.events
	}

	internal fun unregisterServer(deviceAddress: String, uuid: UUID) {
		_activeServers[deviceAddress]?.remove(uuid)
	}

	public fun onActivityResumed() {
		_devices.values.forEach { it.device.onActivityResumed() }
	}

	public fun bondDevices(address1: String, address2: String) {
		if (address1 == address2) {
			return
		}
		_bondingRegistry.getOrPut(address1) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(address2)
		_bondingRegistry.getOrPut(address2) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(address1)

		emitBondState(address1, address2, AndroidBluetoothDevice.BOND_BONDED)
		emitBondState(address2, address1, AndroidBluetoothDevice.BOND_BONDED)
	}

	internal fun initiatePairing(myAddress: String, targetAddress: String) {
		if (isBonded(myAddress, targetAddress)) {
			return
		}

		val myEntry = _devices[myAddress] ?: return

		myEntry.device.launchPairingProcess(targetAddress)
	}

	/**
	 * Unpairs a device locally. This is asymmetric: the target device
	 * will still see this device as paired until it also unpairs.
	 */
	public fun unpairDeviceLocally(myAddress: String, targetAddress: String) {
		if (_bondingRegistry[myAddress]?.remove(targetAddress) == true) {
			emitBondState(myAddress, targetAddress, AndroidBluetoothDevice.BOND_NONE)

			// Unpairing forces disconnection in real Bluetooth stacks
			forceDisconnect(myAddress, targetAddress)
		}
	}

	private fun forceDisconnect(address1: String, address2: String) {
		val pair = if (address1 < address2) address1 to address2 else address2 to address1
		_activeConnections[pair]?.toList()?.forEach { socket ->
			socket.close()
		}
	}

	private fun emitBondState(myAddress: String, otherAddress: String, state: Int) {
		val myEntry = _devices[myAddress] ?: return
		val otherEntry = _devices[otherAddress] ?: return
		myEntry.events.emitDeviceBondState(
			SimulatedLapisBluetoothDevice(
				address = otherAddress,
				name = otherEntry.device.name,
				bondState = state,
				bluetoothEvents = myEntry.events,
				environment = this,
				requesterAddress = myAddress,
			)
		)
	}

	public fun isBonded(myAddress: String, targetAddress: String): Boolean {
		return _bondingRegistry[myAddress]?.contains(targetAddress) == true
	}

	internal fun getBondedDevicesFor(myAddress: String): List<LapisBluetoothDevice> {
		return _bondingRegistry[myAddress].orEmpty()
			.mapNotNull { _devices[it] }
			.map { entry ->
				SimulatedLapisBluetoothDevice(
					address = entry.device.address.value,
					name = entry.device.name,
					bondState = AndroidBluetoothDevice.BOND_BONDED,
					bluetoothEvents = entry.events,
					environment = this,
					requesterAddress = myAddress,
				)
			}
	}

	internal fun requestConnection(
		requesterAddress: String,
		targetAddress: String,
		uuid: UUID,
		isSecureRequest: Boolean,
	): SimulatedLapisBluetoothSocket? {
		println("$$$ request connection1: $requesterAddress, $targetAddress, $uuid, $isSecureRequest")
		val serverEntry = _activeServers[targetAddress]?.get(uuid) ?: return null
		println("$$$ request connection2: $requesterAddress, $targetAddress, $uuid, $isSecureRequest")

		val isSecure = isSecureRequest || serverEntry.isSecure

		// If either side is secure, pairing is required.
		// For testing, we automatically pair them if not already bonded.
		if (isSecure && !isBonded(requesterAddress, targetAddress)) {
			println("Secure connection requested: automatically initiating pairing between $requesterAddress and $targetAddress")
			initiatePairing(requesterAddress, targetAddress)
		}

		val requesterEntry = _devices[requesterAddress] ?: return null
		println("$$$ request connection3: $requesterAddress, $targetAddress, $uuid, $isSecureRequest")
		val targetEntry = _devices[targetAddress] ?: return null
		println("$$$ request connection4: $requesterAddress, $targetAddress, $uuid, $isSecureRequest")

		// Forced failure
		val forcedResult = requesterEntry.config.connectionResult
		if (forcedResult is SimulatedBluetoothConfiguration.ConnectionResult.CouldNotConnect) {
			println("Simulating forced connection failure")
			return null
		}

		val pipe = BidirectionalStreamPipe()

		val clientSocket = SimulatedLapisBluetoothSocket(
			remoteDevice = SimulatedLapisBluetoothDevice(
				address = targetAddress,
				name = targetEntry.device.name,
				bluetoothEvents = requesterEntry.events,
				environment = this,
				requesterAddress = requesterAddress
			),
			inputStream = pipe.sideA.inputStream,
			outputStream = pipe.sideA.outputStream,
			isSecure = isSecure,
		)

		val serverSideSocket = SimulatedLapisBluetoothSocket(
			remoteDevice = SimulatedLapisBluetoothDevice(
				address = requesterAddress,
				name = requesterEntry.device.name,
				bluetoothEvents = targetEntry.events,
				environment = this,
				requesterAddress = targetAddress
			),
			inputStream = pipe.sideB.inputStream,
			outputStream = pipe.sideB.outputStream,
			isSecure = isSecure,
		)

		clientSocket.twin = serverSideSocket
		serverSideSocket.twin = clientSocket

		serverEntry.socket.enqueueIncomingConnection(serverSideSocket)

		return clientSocket
	}

	internal fun registerSocket(socket: SimulatedLapisBluetoothSocket) {
		val address1 = socket.remoteDevice.address
		val address2 = socket.remoteDevice.requesterAddress
		val pair = if (address1 < address2) address1 to address2 else address2 to address1
		val connections = _activeConnections.getOrPut(pair) { Collections.synchronizedList(mutableListOf()) }
		connections.add(socket)
	}

	internal fun unregisterSocket(socket: SimulatedLapisBluetoothSocket) {
		val address1 = socket.remoteDevice.address
		val address2 = socket.remoteDevice.requesterAddress
		val pair = if (address1 < address2) address1 to address2 else address2 to address1
		_activeConnections[pair]?.remove(socket)
	}

	public fun dispose() {
		if (_isDisposed) {
			return
		}
		_isDisposed = true

		scope.cancel()

		_activeServers.forEach { (_, servers) ->
			servers.values.forEach { entry ->
				try {
					entry.socket.close()
				}
				catch (_: Exception) {
				}
			}
		}
		_activeServers.clear()

		_activeConnections.values.forEach { connections ->
			connections.toList().forEach { socket ->
				try {
					socket.close()
				}
				catch (_: Exception) {
				}
			}
		}
		_activeConnections.clear()

		_devices.values.forEach { entry ->
			try {
				entry.device.lapisBt.dispose()
			}
			catch (_: Exception) {
			}
		}
		_devices.clear()

		_bondingRegistry.clear()
	}
}
