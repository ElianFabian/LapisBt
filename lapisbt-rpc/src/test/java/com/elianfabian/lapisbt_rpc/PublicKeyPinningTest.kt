package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.exception.LapisHandshakeException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalBluetoothReflectionApi::class)
class PublicKeyPinningTest {

	private val serviceUuid = UUID.randomUUID()

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `handshake succeeds with correct hardcoded pinned public key hash`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		// 1. Pre-generate a KeyPair for the peripheral (the server)
		val peripheralKeyPair = LapisEncryption.generateKeyPair()
		val peripheralPublicHash = LapisEncryption.calculatePublicKeyHash(peripheralKeyPair.public)

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// 2. Configure Phone with the Peripheral's KNOWN hash
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic(pinnedPublicKeyHash = peripheralPublicHash))

		// 3. Configure Peripheral to use the FIXED KeyPair
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic(keyPair = peripheralKeyPair))

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("PinnedService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		// 4. Verify successful RPC call
		val result = client.echo("Pinned Identity Verified")
		assertThat(result).isEqualTo("Pinned Identity Verified")

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `handshake fails when pinned public key hash mismatch`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Wrong hash provided to phone
		val wrongHash = ByteArray(32) { 0xAA.toByte() }
		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic(pinnedPublicKeyHash = wrongHash))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic())

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("PinnedService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)

		try {
			client.echo("should fail")
			throw AssertionError("Handshake should have failed due to PIN mismatch")
		}
		catch (e: LapisHandshakeException) {
			assertThat(e.message).contains("Pinned public key verification failed")
		}

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `handshake succeeds with mutual pinning`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val deviceA = environment.createDevice()
		val deviceB = environment.createDevice()

		val keyPairA = LapisEncryption.generateKeyPair()
		val hashA = LapisEncryption.calculatePublicKeyHash(keyPairA.public)

		val keyPairB = LapisEncryption.generateKeyPair()
		val hashB = LapisEncryption.calculatePublicKeyHash(keyPairB.public)

		val rpcA = LapisBtRpc.newInstance(deviceA.lapisBt, logger = LapisLogger.console())
		val rpcB = LapisBtRpc.newInstance(deviceB.lapisBt, logger = LapisLogger.console())

		// Device A identifies as A and pins B
		rpcA.setEncryption(deviceB.address, LapisEncryption.automatic(pinnedPublicKeyHash = hashB, keyPair = keyPairA))
		// Device B identifies as B and pins A
		rpcB.setEncryption(deviceA.address, LapisEncryption.automatic(pinnedPublicKeyHash = hashA, keyPair = keyPairB))

		val serverJob = launch {
			deviceB.lapisBt.startBluetoothServerWithoutPairing("MutualService", serviceUuid)
		}

		deviceA.lapisBt.connectToDeviceWithoutPairing(deviceB.address, serviceUuid)
		rpcB.registerBluetoothServerService<SecureService>(deviceA.address, SecureServiceImpl())

		val client = rpcA.getOrCreateBluetoothClientService<SecureService>(deviceB.address)

		// Success only if BOTH pins match
		assertThat(client.echo("Mutual trust")).isEqualTo("Mutual trust")

		serverJob.cancel()
		rpcA.dispose()
		rpcB.dispose()
		environment.dispose()
	}

	@Test
	fun `tofu workflow - learn hash then enforce it`() = runTest(timeout = 20.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val peripheralKeyPair = LapisEncryption.generateKeyPair()
		var capturedHash: ByteArray? = null

		// --- SESSION 1: Learning (TOFU) ---
		val interceptor = object : LapisInterceptor {
			override suspend fun interceptHandshakeSuccess(
				deviceAddress: BluetoothDevice.Address,
				remotePublicKeyBytes: ByteArray,
				sharedSecret: ByteArray,
				sessionKey: ByteArray?,
			) {
				capturedHash = LapisEncryption.calculatePublicKeyHash(remotePublicKeyBytes)
				// Verify we received the keys
				assertThat(sharedSecret).isNotEmpty()
				assertThat(sessionKey).isNotNull()
				assertThat(sessionKey!!.size).isEqualTo(32)
			}
		}

		val phoneRpc1 = LapisBtRpc.newInstance(phone.lapisBt, interceptor = interceptor, logger = LapisLogger.console())
		val peripheralRpc1 = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		phoneRpc1.setEncryption(peripheral.address, LapisEncryption.automatic())
		peripheralRpc1.setEncryption(phone.address, LapisEncryption.automatic(keyPair = peripheralKeyPair))

		val serverJob1 = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("TofuService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc1.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		phoneRpc1.getOrCreateBluetoothClientService<SecureService>(peripheral.address).echo("First contact")

		assertThat(capturedHash).isEqualTo(LapisEncryption.calculatePublicKeyHash(peripheralKeyPair.public))

		serverJob1.cancel()
		phoneRpc1.dispose()
		peripheralRpc1.dispose()
		phone.lapisBt.disconnectFromDevice(peripheral.address)
		delay(500.milliseconds)

		// --- SESSION 2: Enforcing ---
		val phoneRpc2 = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc2 = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Now we PIN the hash we captured in Session 1
		phoneRpc2.setEncryption(peripheral.address, LapisEncryption.automatic(pinnedPublicKeyHash = capturedHash))
		peripheralRpc2.setEncryption(phone.address, LapisEncryption.automatic(keyPair = peripheralKeyPair))

		val serverJob2 = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("TofuService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc2.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		assertThat(phoneRpc2.getOrCreateBluetoothClientService<SecureService>(peripheral.address).echo("Still me"))
			.isEqualTo("Still me")

		serverJob2.cancel()
		phoneRpc2.dispose()
		peripheralRpc2.dispose()
		phone.lapisBt.disconnectFromDevice(peripheral.address)
		delay(500.milliseconds)

		// --- SESSION 3: Protection (MITM / Fake Device) ---
		val fakeKeyPair = LapisEncryption.generateKeyPair()
		val phoneRpc3 = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc3 = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		// Phone still pins to the ORIGINAL hash
		phoneRpc3.setEncryption(peripheral.address, LapisEncryption.automatic(pinnedPublicKeyHash = capturedHash))
		// BUT the device now has a NEW key (impersonation attempt)
		peripheralRpc3.setEncryption(phone.address, LapisEncryption.automatic(keyPair = fakeKeyPair))

		val serverJob3 = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("TofuService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc3.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		try {
			phoneRpc3.getOrCreateBluetoothClientService<SecureService>(peripheral.address).echo("Imposter!")
			throw AssertionError("Handshake should have failed")
		}
		catch (e: LapisHandshakeException) {
			assertThat(e.message).contains("Pinned public key verification failed")
		}

		serverJob3.cancel()
		phoneRpc3.dispose()
		peripheralRpc3.dispose()
		environment.dispose()
	}
}
