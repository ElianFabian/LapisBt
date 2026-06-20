package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.common.util.LapisLogger
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothDevice
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothEnvironment
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
class LapisBtRpcRegularTest {

	private val serviceUuid = UUID.randomUUID()
	private lateinit var environment: SimulatedBluetoothEnvironment
	private lateinit var phone: SimulatedBluetoothDevice
	private lateinit var peripheral: SimulatedBluetoothDevice
	private lateinit var phoneRpc: LapisBtRpc
	private lateinit var peripheralRpc: LapisBtRpc
	private val serviceImpl = RegularServiceImpl()


	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		environment = LapisBt.newSimulatedBluetoothEnvironment(createLogger = { LapisLogger.console() })
		phone = environment.createDevice()
		peripheral = environment.createDevice()
	}

	@After
	fun tearDown() {
		if (::phoneRpc.isInitialized) phoneRpc.dispose()
		if (::peripheralRpc.isInitialized) peripheralRpc.dispose()
		if (::environment.isInitialized) environment.dispose()
		Dispatchers.resetMain()
	}

	private suspend fun TestScope.setupConnection(
		phoneMetadataProvider: LapisMetadataProvider<Any?>? = null,
		peripheralMetadataProvider: LapisMetadataProvider<Any?>? = null,
	) {
		phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, metadataProvider = phoneMetadataProvider, logger = LapisLogger.console())
		peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, metadataProvider = peripheralMetadataProvider, logger = LapisLogger.console())

		peripheralRpc.registerBluetoothServerService<RegularService>(phone.address, serviceImpl)

		backgroundScope.launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("RegularService", serviceUuid)
		}

		val result = phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)
	}

	@Test
	fun `suspend fun noParamNoReturn works`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		client.noParamNoReturn()
		assertThat(serviceImpl.noParamNoReturnCalled).isTrue()
	}

	@Test
	fun `suspend fun noParamWithReturn works`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val result = client.noParamWithReturn()
		assertThat(result).isEqualTo("Hello")
	}

	@Test
	fun `suspend fun withParams works`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val result = client.withParams(10, "abc")
		assertThat(result).isEqualTo(13) // 10 + 3
	}

	@Test
	fun `suspend fun withFlowParam works`() = runTest(timeout = 15.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val flow = flowOf(1, 2, 3, 4, 5)
		val result = client.withFlowParam(flow)
		assertThat(result).isEqualTo(15)
	}

	@Test
	fun `suspend fun withMixedParams works`() = runTest(timeout = 15.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val flow = flowOf(10, 20)
		val result = client.withMixedParams("Sum", flow)
		assertThat(result).isEqualTo("Sum: 30")
	}

	@Test
	fun `fun streamReturn works`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val result = client.streamReturn(5).toList()
		assertThat(result).containsExactly(0, 1, 2, 3, 4).inOrder()
	}

	@Test
	fun `request metadata works`() = runTest(timeout = 10.seconds) {
		val testMetadata = "SecretToken-123"
		this.setupConnection(
			phoneMetadataProvider = StringMetadataProvider(testMetadata) as LapisMetadataProvider<Any?>,
			peripheralMetadataProvider = StringMetadataProvider("") as LapisMetadataProvider<Any?>
		)
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val receivedMetadata = client.checkMetadata()
		assertThat(receivedMetadata).isEqualTo(testMetadata)
	}

	@Test
	fun `concurrent requests work`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val results = (1..5).map { i ->
			async {
				client.delayedEcho("Msg$i", 500)
			}
		}.awaitAll()

		assertThat(results).containsExactly("Msg1", "Msg2", "Msg3", "Msg4", "Msg5").inOrder()
	}

	@Test
	fun `concurrent flows work`() = runTest(timeout = 10.seconds) {
		this.setupConnection()
		val client = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		val results = (1..3).map { i ->
			async {
				val flow = flowOf(i, i + 1)
				client.withFlowParam(flow)
			}
		}.awaitAll()

		assertThat(results).containsExactly(1 + 2, 2 + 3, 3 + 4)
	}

	@Test
	fun `reconnection works`() = runTest(timeout = 20.seconds) {
		this.setupConnection()
		val client1 = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)

		assertThat(client1.noParamWithReturn()).isEqualTo("Hello")

		// Disconnect
		phone.lapisBt.disconnectFromDevice(peripheral.address)
		delay(500) // Ensure disconnection is processed

		// Re-register and Re-connect
		peripheralRpc.registerBluetoothServerService<RegularService>(phone.address, serviceImpl)

		backgroundScope.launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("RegularService", serviceUuid)
		}

		val result = phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		// Obtain new client and verify
		val client2 = phoneRpc.getOrCreateBluetoothClientService<RegularService>(peripheral.address)
		assertThat(client2.noParamWithReturn()).isEqualTo("Hello")
	}
}


@LapisRpc(name = "RegularService")
interface RegularService {

	@LapisMethod("noParamNoReturn")
	suspend fun noParamNoReturn()

	@LapisMethod("noParamWithReturn")
	suspend fun noParamWithReturn(): String

	@LapisMethod("withParams")
	suspend fun withParams(
		@LapisParam("a")
		a: Int,
		@LapisParam("b")
		b: String,
	): Int

	@LapisMethod("withFlowParam")
	suspend fun withFlowParam(
		@LapisParam("flow")
		flow: Flow<Int>,
	): Int

	@LapisMethod("withMixedParams")
	suspend fun withMixedParams(
		@LapisParam("a")
		a: String,
		@LapisParam("flow")
		flow: Flow<Int>,
	): String

	@LapisMethod("streamReturn")
	fun streamReturn(
		@LapisParam("count")
		count: Int,
	): Flow<Int>

	@LapisMethod("checkMetadata")
	suspend fun checkMetadata(): String?

	@LapisMethod("delayedEcho")
	suspend fun delayedEcho(
		@LapisParam("message")
		message: String,
		@LapisParam("delayMs")
		delayMs: Long,
	): String
}

class RegularServiceImpl : RegularService {

	var noParamNoReturnCalled = false

	override suspend fun noParamNoReturn() {
		noParamNoReturnCalled = true
	}

	override suspend fun noParamWithReturn(): String = "Hello"

	override suspend fun withParams(a: Int, b: String): Int = a + b.length

	override suspend fun withFlowParam(flow: Flow<Int>): Int {
		return flow.toList().sum()
	}

	override suspend fun withMixedParams(a: String, flow: Flow<Int>): String {
		val sum = flow.toList().sum()
		return "$a: $sum"
	}

	override fun streamReturn(count: Int): Flow<Int> = (0 until count).toList().asFlow()

	override suspend fun checkMetadata(): String? {
		val info = getLapisRequestInfo()
		return info.request.metadata as? String
	}

	override suspend fun delayedEcho(message: String, delayMs: Long): String {
		delay(delayMs)
		return message
	}
}

class StringMetadataProvider(private val metadataToReturn: String) : LapisMetadataProvider<String> {
	override suspend fun createMetadataForOutgoingRequest(
		deviceAddress: BluetoothDevice.Address,
		requestId: Int,
		serviceName: String,
		methodName: String,
		arguments: Map<String, Any?>,
	): String = metadataToReturn

	override fun serializeMetadata(metadata: String): ByteArray = metadata.toByteArray()

	override fun deserializeMetadata(rawMetadata: ByteArray): String = String(rawMetadata)
}
