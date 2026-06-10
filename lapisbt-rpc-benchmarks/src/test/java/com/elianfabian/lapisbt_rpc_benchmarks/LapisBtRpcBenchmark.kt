package com.elianfabian.lapisbt_rpc_benchmarks

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothDevice
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothEnvironment
import com.elianfabian.lapisbt.util.LapisLogger
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.annotation.LapisMethod
import com.elianfabian.lapisbt_rpc.annotation.LapisParam
import com.elianfabian.lapisbt_rpc.annotation.LapisRpc
import com.elianfabian.lapisbt_rpc.getOrCreateBluetoothClientService
import com.elianfabian.lapisbt_rpc.registerBluetoothServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

// TODO: think about the concrete values of warmup and iterations to see if we should change them
// We implement benchmarks manually using a test source because of the following reasons:
// - In test sources Android classes are available, so we don't have to worry about creating fake versions of those classes
// - I tried using JMH, but it was INSANELY slow, so it's no use
@OptIn(ExperimentalCoroutinesApi::class)
class LapisBtRpcBenchmark {

	private lateinit var environment: SimulatedBluetoothEnvironment
	private lateinit var phone: SimulatedBluetoothDevice
	private lateinit var peripheral: SimulatedBluetoothDevice
	private lateinit var phoneRpc: LapisBtRpc
	private lateinit var peripheralRpc: LapisBtRpc
	private lateinit var client: BenchmarkService
	private val serviceUuid = UUID.randomUUID()

	@Before
	fun setUp() = runBlocking {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		environment = LapisBt.newSimulatedBluetoothEnvironment(
			createLogger = { _ ->
				LapisLogger.Silent
			}
		)
		phone = environment.createDevice()
		peripheral = environment.createDevice()

		phoneRpc = LapisBtRpc.newInstance(phone.lapisBt, logger = LapisLogger.Silent)
		peripheralRpc = LapisBtRpc.newInstance(peripheral.lapisBt, logger = LapisLogger.Silent)

		// Establish connection first to avoid race conditions in RPC layer startup
		launch {
			peripheral.lapisBt.startBluetoothServerWithoutPairing("BenchmarkService", serviceUuid)
		}

		val result = phone.lapisBt.connectToDeviceWithoutPairing(peripheral.address, serviceUuid)
		if (result !is LapisBt.ConnectionResult.ConnectionEstablished) {
			throw RuntimeException("Failed to connect: $result")
		}

		// Register service AFTER connection is established
		peripheralRpc.registerBluetoothServerService<BenchmarkService>(phone.address, BenchmarkServiceImpl())

		client = phoneRpc.getOrCreateBluetoothClientService<BenchmarkService>(peripheral.address)
	}

	@After
	fun tearDown() {
		if (::phoneRpc.isInitialized) phoneRpc.dispose()
		if (::peripheralRpc.isInitialized) peripheralRpc.dispose()
		if (::environment.isInitialized) environment.dispose()
		Dispatchers.resetMain()
	}

	@Test
	fun benchmarkSimpleCall(): Unit = runBlocking {
		Benchmark.run("Simple Suspend Call", warmup = 50, iterations = 200) {
			client.simpleCall()
		}
	}

	@Test
	fun benchmarkEchoCall(): Unit = runBlocking {
		val message = "Hello Benchmark!"
		Benchmark.run("Echo String Call", warmup = 50, iterations = 200) {
			client.echo(message)
		}
	}

	@Test
	fun benchmarkStreamSmall(): Unit = runBlocking {
		Benchmark.run("Stream 10 elements", warmup = 20, iterations = 100) {
			client.stream(10).toList()
		}
	}

	@Test
	fun benchmarkStreamLarge(): Unit = runBlocking {
		Benchmark.run("Stream 1000 elements", warmup = 5, iterations = 5) {
			client.stream(1000).toList() // falla aquí
		}
	}

	@Test
	fun benchmarkConcurrentCalls(): Unit = runBlocking {
		Benchmark.run("10 Concurrent Echo Calls", warmup = 20, iterations = 50) {
			(1..10).map { i ->
				async { client.echo("Msg $i") }
			}.awaitAll()
		}
	}
}


@LapisRpc(name = "BenchmarkService")
interface BenchmarkService {
	@LapisMethod("simpleCall")
	suspend fun simpleCall()

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

class BenchmarkServiceImpl : BenchmarkService {
	override suspend fun simpleCall() {}
	override suspend fun echo(message: String): String = message
	override fun stream(count: Int): Flow<Int> = (0 until count).asFlow()
}
