package com.elianfabian.lapisbt

import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothConfiguration
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothEnvironment
import com.elianfabian.lapisbt.util.AndroidInternalConstants
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalBluetoothReflectionApi::class)
class LapisBtTest {

	lateinit var environment: SimulatedBluetoothEnvironment

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		environment = LapisBt.newSimulatedBluetoothEnvironment(
			createLogger = { address ->
				LapisLogger.console(
					prefix = {
						"$address|"
					}
				)
			}
		)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	// --- Multi-Device Tests ---

	@Test
	fun `devices can discover each other`() = runTest {
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		phone.lapisBt.startScan()

		val discovered = phone.lapisBt.scannedDevices.first { it.isNotEmpty() }
		assertThat(discovered.map { it.device.address }).contains(peripheral.address)
	}

	@Test
	fun `devices can connect and communicate`() = runTest {
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Peripheral starts server
		val serverJob = launch {
			val result = peripheral.lapisBt.startBluetoothServer("TestService", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
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
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			val result = peripheral.lapisBt.startBluetoothServer("TestService", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
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

		serverJob.cancel()
	}

	// --- Security Tests ---

	@Test
	fun `insecure connection works without bonding`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			val result = server.lapisBt.startBluetoothServerWithoutPairing("Test", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		val result = client.lapisBt.connectToDeviceWithoutPairing(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		serverJob.cancel()
	}

	@Test
	fun `secure connection triggers automatic pairing if not bonded`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			val result = server.lapisBt.startBluetoothServer("Test", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
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
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Establish bond first
		environment.bondDevices(client.address.value, server.address.value)

		val serverJob = launch {
			val result = server.lapisBt.startBluetoothServer("Test", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		serverJob.cancel()
	}

	@Test
	fun `mixed security connection triggers automatic pairing if not bonded`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		val serverJob = launch {
			val result = server.lapisBt.startBluetoothServerWithoutPairing("Test", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
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
		val device = environment.createDevice()

		// Xiaomi-like behavior
		device.config.needsLocationForScan = true
		device.config.isLocationEnabled = false

		val started = device.lapisBt.startScan()
		assertThat(started).isNotEqualTo(LapisBt.ScanResult.Success)

		device.setLocationEnabled(true)
		val startedWithLocation = device.lapisBt.startScan()
		assertThat(startedWithLocation).isEqualTo(LapisBt.ScanResult.Success)
	}

	@Test
	fun `permissions can be toggled at runtime`() = runTest {
		val device = environment.createDevice()

		device.setPermissions(connect = false, scan = false)

		// LapisBtImpl.startScan returns a failure result if scan permission is not granted
		assertThat(device.lapisBt.startScan()).isNotEqualTo(LapisBt.ScanResult.Success)

		device.setPermissions(connect = true, scan = true)
		assertThat(device.lapisBt.startScan()).isEqualTo(LapisBt.ScanResult.Success)
	}

	@Test
	fun `forced connection failure`() = runTest {
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
		environment.createDevice()
		environment.createDevice()

		environment.onActivityResumed()
		// Basic verification that call completes
	}

	// --- Unpairing Tests ---

	@Test
	fun `unpairing is asymmetric`() = runTest {
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

	@Test
	fun `unpairing forces disconnection`() = runTest {
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Setup connection
		val serverJob = launch {
			val result = peripheral.lapisBt.startBluetoothServer("Test", serviceUuid)
			assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
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

		serverJob.cancel()
	}

	// --- Failure Scenarios Tests ---

	@Test
	fun `scan fails when bluetooth is disabled`() = runTest {
		val device = environment.createDevice()

		device.setBluetoothState(LapisBt.BluetoothState.Off)

		val result = device.lapisBt.startScan()
		assertThat(result).isEqualTo(LapisBt.ScanResult.BluetoothDisabled)
	}

	@Test
	fun `scan fails when bluetooth is not supported`() = runTest {
		val device = environment.createDevice()

		device.config.isBluetoothSupported = false

		val result = device.lapisBt.startScan()
		assertThat(result).isEqualTo(LapisBt.ScanResult.BluetoothNotSupported)
	}

	@Test
	fun `scan fails when bluetooth scan permission is missing`() = runTest {
		val device = environment.createDevice()

		device.config.isBluetoothScanGranted = false
		device.config.isAccessFineLocationGranted = false
		device.config.isAccessCoarseLocationGranted = false

		val result = device.lapisBt.startScan()
		assertThat(result).isAnyOf(
			LapisBt.ScanResult.MissingBluetoothScanPermission,
			LapisBt.ScanResult.MissingLocationPermission
		)
	}

	@Test
	fun `scan fails when scan is already in progress`() = runTest {
		val device = environment.createDevice()

		device.lapisBt.startScan()
		val result = device.lapisBt.startScan()
		assertThat(result).isEqualTo(LapisBt.ScanResult.ScanAlreadyInProgress)
	}

	@Test
	fun `connection fails when bluetooth is disabled`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		client.setBluetoothState(LapisBt.BluetoothState.Off)

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isEqualTo(LapisBt.ConnectionResult.BluetoothDisabled)
	}

	@Test
	fun `connection fails when permission is missing`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		client.config.isBluetoothConnectGranted = false

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isEqualTo(LapisBt.ConnectionResult.MissingPermission)
	}

	@Test
	fun `connection fails when bluetooth is not supported`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		client.config.isBluetoothSupported = false

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isEqualTo(LapisBt.ConnectionResult.BluetoothNotSupported)
	}

	@Test
	fun `connection fails when server cannot accept`() = runTest {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		// Force server side connection failure
		server.config.connectionResult = SimulatedBluetoothConfiguration.ConnectionResult.CouldNotConnect

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)
		assertThat(result).isEqualTo(LapisBt.ConnectionResult.CouldNotConnect)
	}

	@Test
	fun `pairing fails when forced via config`() = runTest(timeout = 15.seconds) {
		val client = environment.createDevice()
		val server = environment.createDevice()
		val serviceUuid = UUID.randomUUID()

		client.config.pairingResult = SimulatedBluetoothConfiguration.PairingResult.Failure(reason = AndroidInternalConstants.UNBOND_REASON_AUTH_FAILED)

		// Use connectToDevice to trigger pairing
		val serverJob = launch {
			server.lapisBt.startBluetoothServer("Test", serviceUuid)
		}
		server.lapisBt.activeBluetoothServersUuids.first { it.contains(serviceUuid) }

		val result = client.lapisBt.connectToDevice(server.address, serviceUuid)

		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.CouldNotConnect::class.java)
		serverJob.cancel()
	}

	@Test
	fun `isBluetoothClassicSupported reflects hardware state`() = runTest {
		val device = environment.createDevice()

		assertThat(device.lapisBt.isBluetoothClassicSupported).isTrue()

		device.config.isBluetoothSupported = false
		assertThat(device.lapisBt.isBluetoothClassicSupported).isFalse()
	}
}
