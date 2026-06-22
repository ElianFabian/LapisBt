package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.LapisEncryptionException
import com.elianfabian.lapisbt_rpc.exception.LapisHandshakeException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
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
class LapisBtRpcEncryptionTest {

	private val serviceUuid = UUID.randomUUID()
	private val aesKey = ByteArray(32) { it.toByte() }


	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `rpc call works with encryption enabled`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.aesGcm(aesKey))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.aesGcm(aesKey))

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		// Connect on phone (it will wait for server to be ready due to simulated environment improvements)
		val connectResult = phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		assertThat(connectResult).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		// Get client on phone
		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		// Call method
		val message = "Hello, Encrypted World!"
		val result = client.echo(message)
		assertThat(result).isEqualTo(message)

		// Test Flow
		val flowResult = client.stream(5).toList()
		assertThat(flowResult).containsExactly(0, 1, 2, 3, 4).inOrder()

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc call fails when encryption is mismatched`() = runTest(timeout = 15.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Enable encryption ONLY on phone
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.aesGcm(aesKey))
		// peripheralRpc does NOT have encryption set
		peripheralRpc.setEncryption(phone.address, null)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		try {
			client.echo("fail")
		}
		catch (_: Exception) {
			// Expected
		}

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc works after handshake key exchange`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		val clientRandomKey = ByteArray(16) { (it + 1).toByte() }
		val serverRandomKey = ByteArray(16) { (it + 100).toByte() }

		val handshakeServiceImpl = HandshakeServiceImpl(serverRandomKey)
		peripheralRpc.registerBluetoothServerService<HandshakeService>(phone.address, handshakeServiceImpl)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)

		// 1. Plaintext Handshake
		val handshakeClient = phoneRpc.getOrCreateBluetoothClientService<HandshakeService>(peripheral.address)
		val receivedServerKey = handshakeClient.exchangeKey(clientRandomKey)

		assertThat(handshakeServiceImpl.receivedClientKey).isEqualTo(clientRandomKey)
		assertThat(receivedServerKey).isEqualTo(serverRandomKey)

		// 2. Derive Session Key (simple XOR for test)
		val sessionKey = ByteArray(16) { i ->
			(clientRandomKey[i].toInt() xor receivedServerKey[i].toInt()).toByte()
		}

		// 3. Enable Encryption
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.aesGcm(sessionKey))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.aesGcm(sessionKey))

		// 4. Encrypted Call
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())
		val secureClient = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		val message = "Handshake Successful"
		assertThat(secureClient.echo(message)).isEqualTo(message)

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc works with automatic encryption`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic())
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic())

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		val message = "Hello, Automatic World!"
		assertThat(client.echo(message)).isEqualTo(message)

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc works with custom automatic encryption`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Custom factory that just uses the shared secret directly (not recommended for production but fine for test)
		val factory = { sharedSecret: ByteArray -> LapisEncryption.aesGcm(sharedSecret.copyOf(32)) }

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic(factory))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic(factory))

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		val message = "Hello, Custom Automatic World!"
		assertThat(client.echo(message)).isEqualTo(message)

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc call fails with LapisEncryptionException when one side doesn't have encryption`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Phone requires encryption
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.aesGcm(aesKey))
		// Peripheral does NOT have encryption set
		peripheralRpc.setEncryption(phone.address, null)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		try {
			client.echo("fail")
			throw AssertionError("Should have thrown LapisEncryptionException")
		}
		catch (e: LapisEncryptionException) {
			// Expected
		}

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `handshake times out when remote doesn't respond`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Phone is automatic
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic())
		// Peripheral DOES NOT have encryption configured, so it won't respond to Handshake packets
		peripheralRpc.setEncryption(phone.address, null)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		// Handshake starts on call. It should timeout.
		try {
			client.echo("timeout")
			throw AssertionError("Should have thrown LapisHandshakeException")
		}
		catch (e: LapisHandshakeException) {
			// Expected
		}

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}
}


@LapisRpc("HandshakeService")
interface HandshakeService {

	@LapisMethod("exchangeKey")
	suspend fun exchangeKey(
		@LapisParam("clientKey")
		clientKey: ByteArray,
	): ByteArray
}

class HandshakeServiceImpl(private val serverKey: ByteArray) : HandshakeService {
	var receivedClientKey: ByteArray? = null

	override suspend fun exchangeKey(clientKey: ByteArray): ByteArray {
		receivedClientKey = clientKey
		return serverKey
	}
}

@LapisRpc("SecureService")
interface SecureService {

	@LapisMethod("echo")
	suspend fun echo(
		@LapisParam("message")
		message: String,
	): String

	@LapisMethod("stream")
	fun stream(
		@LapisParam("count")
		count: Int,
	): Flow<Int>
}

class SecureServiceImpl : SecureService {

	override suspend fun echo(message: String): String = message
	override fun stream(count: Int): Flow<Int> = (0 until count).toList().asFlow()
}
