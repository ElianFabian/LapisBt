package com.elianfabian.lapisbt.fake

import android.content.Context
import com.elianfabian.lapisbt.LapisBtImpl
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.elianfabian.lapisbt.util.BidirectionalStreamPipe
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

public class FakeBluetoothEnvironment(
	seed: Long = 1L,
	public val globalConfig: FakeBluetoothConfiguration = FakeBluetoothConfiguration(),
	private val context: Context? = null,
) {
	private val random = Random(seed)
	private val devices = ConcurrentHashMap<String, FakeBluetoothDeviceEntry>()
	private val activeServers = ConcurrentHashMap<String, MutableMap<UUID, ServerEntry>>()
	private val bondingRegistry = ConcurrentHashMap<String, MutableSet<String>>()
	private val activeConnections = ConcurrentHashMap<Pair<String, String>, MutableList<LapisBluetoothSocketFake>>()

	internal data class FakeBluetoothDeviceEntry(
		val device: FakeBluetoothDevice,
		val adapter: LapisBluetoothAdapterFake,
		val events: LapisBluetoothEventsFake,
		val config: FakeBluetoothConfiguration,
	)

	internal data class ServerEntry(
		val socket: LapisBluetoothServerSocketFake,
		val isSecure: Boolean,
	)

	public fun createDevice(
		address: String = generateAddress(),
		name: String = "Device ${address.takeLast(5)}",
		config: FakeBluetoothConfiguration = globalConfig.copy(),
	): FakeBluetoothDevice {
		val eventsFake = LapisBluetoothEventsFake(context)
		val adapterFake = LapisBluetoothAdapterFake(
			bluetoothEventsFake = eventsFake,
			config = config,
			environment = this,
			context = context,
		)
		adapterFake.address = address
		adapterFake.setName(name)

		val androidHelperFake = AndroidHelperFake(
			config = config,
			context = context,
		)

		val lapisBt = LapisBtImpl(
			lapisAdapter = adapterFake,
			androidHelper = androidHelperFake,
			bluetoothEvents = eventsFake,
		)

		val fakeDevice = FakeBluetoothDevice(
			address = BluetoothDevice.Address(address),
			lapisBt = lapisBt,
			config = config,
			events = eventsFake,
		)
		val entry = FakeBluetoothDeviceEntry(
			device = fakeDevice,
			adapter = adapterFake,
			events = eventsFake,
			config = config,
		)
		devices[address] = entry
		return fakeDevice
	}

	private fun generateAddress(): String {
		return List(6) { random.nextInt(0, 256) }.joinToString(":") { "%02X".format(it) }
	}

	internal fun getScannableDevices(requesterAddress: String): List<LapisBluetoothDevice> {
		return devices.values
			.filter { it.device.address.value != requesterAddress }
			.map { entry ->
				LapisBluetoothDeviceFake(
					address = entry.device.address.value,
					name = entry.device.name,
					bluetoothEventsFake = entry.events,
					environment = this,
					requesterAddress = requesterAddress,
				)
			}
	}

	internal fun registerServer(deviceAddress: String, uuid: UUID, socket: LapisBluetoothServerSocketFake, isSecure: Boolean) {
		activeServers.getOrPut(deviceAddress) { ConcurrentHashMap() }[uuid] = ServerEntry(socket, isSecure)
	}

	public fun getDeviceConfig(address: String): FakeBluetoothConfiguration? {
		return devices[address]?.config
	}

	internal fun unregisterServer(deviceAddress: String, uuid: UUID) {
		activeServers[deviceAddress]?.remove(uuid)
	}

	public fun onActivityResumed() {
		devices.values.forEach { it.device.onActivityResumed() }
	}

	public fun bondDevices(address1: String, address2: String) {
		if (address1 == address2) {
			return
		}
		bondingRegistry.getOrPut(address1) { java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()) }.add(address2)
		bondingRegistry.getOrPut(address2) { java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()) }.add(address1)
		
		emitBondState(address1, address2, AndroidBluetoothDevice.BOND_BONDED)
		emitBondState(address2, address1, AndroidBluetoothDevice.BOND_BONDED)
	}

	/**
	 * Unpairs a device locally. This is asymmetric: the target device 
	 * will still see this device as paired until it also unpairs.
	 */
	public fun unpairDeviceLocally(myAddress: String, targetAddress: String) {
		if (bondingRegistry[myAddress]?.remove(targetAddress) == true) {
			emitBondState(myAddress, targetAddress, AndroidBluetoothDevice.BOND_NONE)
			
			// Unpairing forces disconnection in real Bluetooth stacks
			forceDisconnect(myAddress, targetAddress)
		}
	}

	private fun forceDisconnect(address1: String, address2: String) {
		val pair = if (address1 < address2) address1 to address2 else address2 to address1
		activeConnections[pair]?.toList()?.forEach { socket ->
			socket.close()
		}
	}

	private fun emitBondState(myAddress: String, otherAddress: String, state: Int) {
		val myEntry = devices[myAddress] ?: return
		val otherEntry = devices[otherAddress] ?: return
		myEntry.events.emitDeviceBondState(
			LapisBluetoothDeviceFake(
				address = otherAddress,
				name = otherEntry.device.name,
				bondState = state,
				bluetoothEventsFake = myEntry.events,
				environment = this,
				requesterAddress = myAddress,
			)
		)
	}

	public fun isBonded(myAddress: String, targetAddress: String): Boolean {
		return bondingRegistry[myAddress]?.contains(targetAddress) == true
	}

	internal fun getBondedDevicesFor(myAddress: String): List<LapisBluetoothDevice> {
		return bondingRegistry[myAddress].orEmpty()
			.mapNotNull { devices[it] }
			.map { entry ->
				LapisBluetoothDeviceFake(
					address = entry.device.address.value,
					name = entry.device.name,
					bondState = AndroidBluetoothDevice.BOND_BONDED,
					bluetoothEventsFake = entry.events,
					environment = this,
					requesterAddress = myAddress
				)
			}
	}

	internal fun requestConnection(
		requesterAddress: String,
		targetAddress: String,
		uuid: UUID,
		isSecureRequest: Boolean,
	): LapisBluetoothSocketFake? {
		val serverEntry = activeServers[targetAddress]?.get(uuid) ?: return null

		// If either side is secure, pairing is required.
		// For testing, we automatically pair them if not already bonded.
		if (isSecureRequest || serverEntry.isSecure) {
			if (!isBonded(requesterAddress, targetAddress)) {
				println("Secure connection requested: automatically pairing $requesterAddress and $targetAddress")
				bondDevices(requesterAddress, targetAddress)
			}
		}

		val requesterEntry = devices[requesterAddress] ?: return null
		val targetEntry = devices[targetAddress] ?: return null

		// Forced failure
		val forcedResult = requesterEntry.config.connectionResult
		if (forcedResult is FakeBluetoothConfiguration.ConnectionResult.CouldNotConnect) {
			println("Simulating forced connection failure")
			return null
		}

		val pipe = BidirectionalStreamPipe()

		val clientSocket = LapisBluetoothSocketFake(
			remoteDevice = LapisBluetoothDeviceFake(
				address = targetAddress,
				name = targetEntry.device.name,
				bluetoothEventsFake = requesterEntry.events,
				environment = this,
				requesterAddress = requesterAddress
			),
			inputStream = pipe.sideA.inputStream,
			outputStream = pipe.sideA.outputStream
		)

		val serverSideSocket = LapisBluetoothSocketFake(
			remoteDevice = LapisBluetoothDeviceFake(
				address = requesterAddress,
				name = requesterEntry.device.name,
				bluetoothEventsFake = targetEntry.events,
				environment = this,
				requesterAddress = targetAddress
			),
			inputStream = pipe.sideB.inputStream,
			outputStream = pipe.sideB.outputStream
		)

		clientSocket.twin = serverSideSocket
		serverSideSocket.twin = clientSocket
		
		// Track connection for forced disconnection
		val pair = if (requesterAddress < targetAddress) requesterAddress to targetAddress else targetAddress to requesterAddress
		val connections = activeConnections.getOrPut(pair) { java.util.Collections.synchronizedList(mutableListOf()) }
		connections.add(clientSocket)
		connections.add(serverSideSocket)

		serverEntry.socket.enqueueIncomingConnection(serverSideSocket)

		return clientSocket
	}
	
	internal fun unregisterSocket(socket: LapisBluetoothSocketFake) {
		val addr1 = socket.remoteDevice.address
		val addr2 = socket.remoteDevice.requesterAddress
		val pair = if (addr1 < addr2) addr1 to addr2 else addr2 to addr1
		activeConnections[pair]?.remove(socket)
	}
}
