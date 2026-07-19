package com.elianfabian.lapisbt_rpc

import com.elianfabian.LapisBtRpcConfig
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.exception.LapisHandshakeException
import com.elianfabian.lapisbt_rpc.util.LapisKeyExchange
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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
	fun `rpc call works with manual encryption enabled`() = runTest(timeout = 10.seconds) {
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

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)
		assertThat(client.echo("Hello")).isEqualTo("Hello")

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc works with automatic encryption and ecdh handshake`() = runTest(timeout = 10.seconds) {
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
		assertThat(client.echo("Auto")).isEqualTo("Auto")

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `rpc works after manual handshake key exchange`() = runTest(timeout = 15.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Generate real EC keys for the manual exchange
		val phoneKeyPair = LapisEncryption.generateKeyPair()
		val peripheralKeyPair = LapisEncryption.generateKeyPair()

		val handshakeServiceImpl = HandshakeServiceImpl(peripheralKeyPair.public.encoded)
		peripheralRpc.registerBluetoothServerService<HandshakeService>(phone.address, handshakeServiceImpl)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)

		// 1. Manual exchange over plaintext RPC
		val handshakeClient = phoneRpc.getOrCreateBluetoothClientService<HandshakeService>(peripheral.address)
		val receivedPeripheralPublicKey = handshakeClient.exchangeKey(phoneKeyPair.public.encoded)

		// 2. Derive the Shared Secret on both sides
		val phoneSharedSecret = LapisKeyExchange.deriveSharedSecret(phoneKeyPair.private, receivedPeripheralPublicKey)
		val peripheralSharedSecret = LapisKeyExchange.deriveSharedSecret(peripheralKeyPair.private, handshakeServiceImpl.receivedClientKey!!)

		// 3. Activate encryption using the fromSharedSecret helper
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.fromSharedSecret(phoneSharedSecret))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.fromSharedSecret(peripheralSharedSecret))

		// 4. Verify encrypted communication
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())
		val secureClient = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		assertThat(secureClient.echo("Manual success")).isEqualTo("Manual success")

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
		val peripheralRpc = LapisBtRpc.newInstance(
			peripheral.lapisBt,
			logger = LapisLogger.console(),
			config = LapisBtRpcConfig(handshakeTimeout = 3.seconds)
		)

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic())
		// Peripheral does not support encryption
		peripheralRpc.setEncryption(phone.address, null)

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("SecureService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		try {
			client.echo("timeout")
			throw AssertionError("Should have timed out")
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
