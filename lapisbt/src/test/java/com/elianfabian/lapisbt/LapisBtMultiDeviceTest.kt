package com.elianfabian.lapisbt

import com.elianfabian.lapisbt.fake.FakeBluetoothEnvironment
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

@OptIn(ExperimentalCoroutinesApi::class)
class LapisBtMultiDeviceTest {

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `devices can discover each other`() = runTest {
		val environment = FakeBluetoothEnvironment(seed = 42L)
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		phone.lapisBt.startScan()

		val discovered = phone.lapisBt.scannedDevices.first { it.isNotEmpty() }
		assertThat(discovered.map { it.address }).contains(peripheral.address)
	}

	@Test
	fun `devices can connect and communicate`() = runTest {
		val environment = FakeBluetoothEnvironment(seed = 123L)
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
		val environment = FakeBluetoothEnvironment()
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
}
