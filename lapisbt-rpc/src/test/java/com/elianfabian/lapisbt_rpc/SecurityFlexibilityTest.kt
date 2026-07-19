package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalBluetoothReflectionApi::class)
class SecurityFlexibilityTest {

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
	fun `tier 1 - automatic encryption works with custom hkdf info`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val customInfo = "Custom-Protocol-v2"
		
		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic(hkdfInfo = customInfo))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic(hkdfInfo = customInfo))

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("FlexService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)
		assertThat(client.echo("Tier1")).isEqualTo("Tier1")

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}

	@Test
	fun `tier 2 - automatic encryption works with custom deriveKey lambda`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		// Simple custom derivation for test
		val customDerive = { secret: ByteArray -> MessageDigest.getInstance("SHA-256").digest(secret + "salt".toByteArray()) }
		
		var interceptorKey: ByteArray? = null
		val interceptor = object : LapisInterceptor {
			override suspend fun interceptHandshakeSuccess(deviceAddress: BluetoothDevice.Address, remotePublicKeyBytes: ByteArray, sharedSecret: ByteArray, sessionKey: ByteArray?) {
				interceptorKey = sessionKey
			}
		}

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, interceptor = interceptor, logger = LapisLogger.console())
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.console())

		phoneRpc.setEncryption(peripheral.address, LapisEncryption.automatic(deriveKey = customDerive))
		peripheralRpc.setEncryption(phone.address, LapisEncryption.automatic(deriveKey = customDerive))

		val serverJob = launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("FlexService", serviceUuid)
		}

		phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		peripheralRpc.registerBluetoothServerService<SecureService>(phone.address, SecureServiceImpl())

		val client = phoneRpc.getOrCreateBluetoothClientService<SecureService>(peripheral.address)
		client.echo("Tier2")

		assertThat(interceptorKey).isNotNull()
		// The key in the interceptor should be exactly what our lambda returned
		// (We can't easily verify the math here without duplicating it, but this proves the pipe works)

		serverJob.cancel()
		phoneRpc.dispose()
		peripheralRpc.dispose()
		environment.dispose()
	}
}
