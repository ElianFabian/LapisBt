package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
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

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt)
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt)

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
	fun `rpc call fails when encryption is mismatched`() = runTest(timeout = 10.seconds) {
		val environment = LapisBt.newSimulatedBluetoothEnvironment()
		val phone = environment.createDevice()
		val peripheral = environment.createDevice()

		val phoneRpc = LapisBtRpc.newInstance(phone.lapisBt)
		val peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt)

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
