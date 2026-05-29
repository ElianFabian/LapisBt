package com.elianfabian.lapisbt

import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothConfiguration
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class, InternalBluetoothReflectionApi::class)
class LapisBtTest {

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	// --- Multi-Device Tests ---

	@Test
	fun `devices can discover each other`() = runTest {
		val environment = SimulatedBluetoothEnvironment(seed = 42L)
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		phone.lapisBt.startScan()

		val discovered = phone.lapisBt.scannedDevices.first { it.isNotEmpty() }
		assertThat(discovered.map { it.address }).contains(peripheral.address)
	}

	@Test
	fun `devices can connect and communicate`() = runTest {
		val environment = SimulatedBluetoothEnvironment(seed = 123L)
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Peripheral starts server
		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServer("TestService", serviceUuid)
		}

		// Wait for server to be registered
		peripheral.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		// Phone finds peripheral (skip scan for brevity, use address directly)
		val connectResult = phone.lapisBt.connectToDevice(peripheral.address, serviceUuid)
		assertThat(connectResult).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		// Verify connected state on both sides
		phone.lapisBt.connectedDevices.first { it.isNotEmpty() }
		peripheral.lapisBt.connectedDevices.first { it.isNotEmpty() }

		assertThat(phone.lapisBt.connectedDevices.value.map { it.address }).contains(peripheral.address)
		assertThat(peripheral.lapisBt.connectedDevices.value.map { it.address }).contains(phone.address)

		// Send data from phone to peripheral
		val message = "Hello from Phone"
		phone.lapisBt.sendData(peripheral.address) { stream ->
			stream.write(message.toByteArray())
			stream.flush()
		}

		// Receive data on peripheral
		var receivedMessage = ""
		peripheral.lapisBt.receiveData(phone.address) { stream ->
			val buffer = ByteArray(message.length)
			val bytesRead = stream.read(buffer)
			receivedMessage = String(buffer, 0, bytesRead)
		}
		assertThat(receivedMessage).isEqualTo(message)

		// Send data back from peripheral to phone
		val reply = "Hi from Peripheral"
		peripheral.lapisBt.sendData(phone.address) { stream ->
			stream.write(reply.toByteArray())
			stream.flush()
		}

		// Receive data on phone
		var receivedReply = ""
		phone.lapisBt.receiveData(peripheral.address) { stream ->
			val buffer = ByteArray(reply.length)
			val bytesRead = stream.read(buffer)
			receivedReply = String(buffer, 0, bytesRead)
		}
		assertThat(receivedReply).isEqualTo(reply)

		serverJob.cancel()
	}

	@Test
	fun `disconnection is reflected on both sides`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		launch {
			peripheral.lapisBt.startBluetoothServer("TestService", serviceUuid)
		}

		// Wait for server to be registered
		peripheral.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		phone.lapisBt.connectToDevice(peripheral.address, serviceUuid)

		phone.lapisBt.connectedDevices.first { it.isNotEmpty() }

		// Phone disconnects
		phone.lapisBt.disconnectFromDevice(peripheral.address)

		// Verify both sides see disconnection
		phone.lapisBt.connectedDevices.first { it.isEmpty() }
		peripheral.lapisBt.connectedDevices.first { it.isEmpty() }
	}

	// --- Security Tests ---

	@Test
	fun `insecure connection works without bonding`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		launch {
			server.lapisBt.startBluetoothServerWithoutPairing("Test", serviceUuid)
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		val result = client.lapisBt.connectToDeviceWithoutPairing(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
	}

	@Test
	fun `secure connection triggers automatic pairing if not bonded`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			try {
				server.lapisBt.startBluetoothServer("Test", serviceUuid)
			}
			catch (e: Exception) {
				println("Server error: ${e.message}")
				e.printStackTrace()
			}
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		assertThat(environment.isBonded(client.address.value, server.address.value)).isFalse()

		// Secure client connecting to secure server -> should trigger automatic pairing
		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		assertThat(environment.isBonded(client.address.value, server.address.value)).isTrue()

		serverJob.cancel()
	}

	@Test
	fun `secure connection works if bonded`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Establish bond first
		environment.bondDevices(client.address.value, server.address.value)

		val serverJob = launch {
			try {
				server.lapisBt.startBluetoothServer("Test", serviceUuid)
			}
			catch (e: Exception) {
				println("Server error: ${e.message}")
				e.printStackTrace()
			}
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		serverJob.cancel()
	}

	@Test
	fun `mixed security connection triggers automatic pairing if not bonded`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			try {
				server.lapisBt.startBluetoothServerWithoutPairing("Test", serviceUuid)
			}
			catch (e: Exception) {
				println("Server error: ${e.message}")
				e.printStackTrace()
			}
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		assertThat(environment.isBonded(client.address.value, server.address.value)).isFalse()

		// Secure client connecting to insecure server -> should trigger automatic pairing
		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		assertThat(environment.isBonded(client.address.value, server.address.value)).isTrue()

		serverJob.cancel()
	}

	// --- Customization & Error Handling Tests ---

	@Test
	fun `bluetooth state transitions are reflected`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val device = environment.createDevice()

		assertThat(device.lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.On)

		device.setBluetoothState(LapisBt.BluetoothState.Off)
		device.lapisBt.state.first { it == LapisBt.BluetoothState.Off }
		assertThat(device.lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.Off)

		device.setBluetoothState(LapisBt.BluetoothState.TurningOn)
		device.lapisBt.state.first { it == LapisBt.BluetoothState.TurningOn }
		assertThat(device.lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.TurningOn)
	}

	@Test
	fun `scan fails when location is required but disabled`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val device = environment.createDevice()

		// Xiaomi-like behavior
		device.config.needsLocationForScan = true
		device.config.isLocationEnabled = false

		val started = device.lapisBt.startScan()
		assertThat(started).isFalse()

		device.setLocationEnabled(true)
		val startedWithLocation = device.lapisBt.startScan()
		assertThat(startedWithLocation).isTrue()
	}

	@Test
	fun `permissions can be toggled at runtime`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val device = environment.createDevice()

		device.setPermissions(connect = false, scan = false)

		// LapisBtImpl.startScan returns false if scan permission is not granted
		assertThat(device.lapisBt.startScan()).isFalse()

		device.setPermissions(connect = true, scan = true)
		assertThat(device.lapisBt.startScan()).isTrue()
	}

	@Test
	fun `forced connection failure`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Force client side connection failure
		client.config.connectionResult = SimulatedBluetoothConfiguration.ConnectionResult.CouldNotConnect

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.CouldNotConnect::class.java)
	}

	// --- Lifecycle Tests ---

	@Test
	fun `onActivityResumed triggers refresh`() = runTest {
		val environment = SimulatedBluetoothEnvironment(context = null)
		val device = environment.createDevice()

		// Initial resolve
		assertThat(device.lapisBt.isScanning.value).isFalse()

		// Call lifecycle event - should not crash
		device.onActivityResumed()
	}

	@Test
	fun `environment onActivityResumed notifies all devices`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		environment.createDevice()
		environment.createDevice()

		environment.onActivityResumed()
		// Basic verification that call completes
	}

	// --- Unpairing Tests ---

	@Test
	fun `unpairing is asymmetric`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		// Establish bidirectional bond
		environment.bondDevices(phone.address.value, peripheral.address.value)
		assertThat(environment.isBonded(phone.address.value, peripheral.address.value)).isTrue()
		assertThat(environment.isBonded(peripheral.address.value, phone.address.value)).isTrue()

		// Phone unpairs peripheral
		phone.lapisBt.unpairDevice(peripheral.address)

		// Verify Phone sees peripheral as unbonded
		assertThat(environment.isBonded(phone.address.value, peripheral.address.value)).isFalse()
		// Verify Peripheral STILL sees phone as bonded
		assertThat(environment.isBonded(peripheral.address.value, phone.address.value)).isTrue()
	}

	// FIXME: this test now times out, see why
	@Test
	fun `unpairing forces disconnection`() = runTest {
		val environment = SimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Setup connection
		launch {
			peripheral.lapisBt.startBluetoothServer("Test", serviceUuid)
		}
		peripheral.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)

		// Ensure both see as connected
		phone.lapisBt.connectedDevices.first { it.isNotEmpty() }
		peripheral.lapisBt.connectedDevices.first { it.isNotEmpty() }

		// Establish bond for the test (even if connection was insecure)
		environment.bondDevices(phone.address.value, peripheral.address.value)

		// Phone unpairs peripheral
		phone.lapisBt.unpairDevice(peripheral.address)

		// Verify connection is closed on both sides
		phone.lapisBt.connectedDevices.first { it.isEmpty() }
		peripheral.lapisBt.connectedDevices.first { it.isEmpty() }
	}
}
