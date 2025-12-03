package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.fake.AndroidHelperFake
import com.elianfabian.lapisbt.fake.LapisBluetoothAdapterFake
import com.elianfabian.lapisbt.fake.LapisBluetoothEventsFake
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class LapisBtImplTest {

	private lateinit var lapisBt: LapisBt
	private lateinit var androidHelperFake: AndroidHelperFake
	private lateinit var bluetoothEventsFake: LapisBluetoothEventsFake
	private lateinit var lapisAdapterFake: LapisBluetoothAdapterFake

	@OptIn(ExperimentalCoroutinesApi::class)
	@Before
	fun setUp() {
		Dispatchers.setMain(StandardTestDispatcher())

		androidHelperFake = AndroidHelperFake(
			isBluetoothSupportedResult = true,
			isBluetoothConnectGrantedResult = true,
			isBluetoothScanGrantedResult = true,
		)

		bluetoothEventsFake = LapisBluetoothEventsFake()

		lapisAdapterFake = LapisBluetoothAdapterFake(
			bluetoothEventsFake = bluetoothEventsFake,
			isEnabled = true,
		)


		lapisBt = LapisBtImpl(
			androidHelper = androidHelperFake,
			bluetoothEvents = bluetoothEventsFake,
			lapisAdapter = lapisAdapterFake,
		)
	}

	@After
	fun tearDown() {

	}


	@Test
	fun `bluetooth support and permissions`() {
		assertThat(lapisBt.isBluetoothSupported).isTrue()
		androidHelperFake.isBluetoothSupportedResult = false
		assertThat(lapisBt.isBluetoothSupported).isFalse()

		assertThat(lapisBt.canEnableBluetooth).isTrue()
		androidHelperFake.isBluetoothConnectGrantedResult = false
		assertThat(lapisBt.canEnableBluetooth).isFalse()
	}

	@Test
	fun `start scan sets isScanning true`() = runTest(timeout = ShortTimeout) {
		assertThat(lapisBt.startScan()).isTrue()
		lapisBt.isScanning.first { it }
		assertThat(lapisBt.isScanning.value).isTrue()
	}

	@Test
	fun `stop scan sets isScanning false`() = runTest(timeout = ShortTimeout) {
		lapisBt.startScan()
		lapisBt.isScanning.first { it }

		assertThat(lapisBt.stopScan()).isTrue()
		lapisBt.isScanning.first { !it }
		assertThat(lapisBt.isScanning.value).isFalse()
	}

	@Test
	fun `scan fails when permission denied`() {
		androidHelperFake.isBluetoothScanGrantedResult = false

		assertThat(lapisBt.startScan()).isFalse()
		assertThat(lapisBt.stopScan()).isFalse()
	}


	@Test
	fun `start scan triggers scannedDevicesFlow`() = runTest(timeout = ShortTimeout) {
		val scannedDevicesAddress = mutableListOf<String>()

		val expectedScannedDevicesAddresses = lapisAdapterFake.getScannableDevices()
			.map { it.address }

		val job = launch {
			lapisBt.scannedDevicesFlow.collect { device ->
				scannedDevicesAddress.add(device.address)
				if (scannedDevicesAddress.size >= expectedScannedDevicesAddresses.size) {
					cancel()
				}
			}
		}

		lapisBt.startScan()
		lapisBt.isScanning.first { it }

		job.join()

		assertThat(scannedDevicesAddress).isEqualTo(expectedScannedDevicesAddresses)

		job.cancel()
	}

	@Test
	fun `setBluetoothDeviceName changes the name`() = runTest(timeout = ShortTimeout) {
		val newName = "New Bluetooth Name"
		assertThat(lapisBt.setBluetoothDeviceName(newName)).isTrue()
		lapisBt.bluetoothDeviceName.first { it == newName }
		assertThat(lapisBt.bluetoothDeviceName.value).isEqualTo(newName)
	}

	@Test
	fun `setBluetoothDeviceName fails when permission denied`() = runTest(timeout = ShortTimeout) {
		androidHelperFake.isBluetoothConnectGrantedResult = false
		val newName = "New Bluetooth Name"
		assertThat(lapisBt.setBluetoothDeviceName(newName)).isFalse()
	}

	@Test
	fun `bluetoothState reflects adapter state`() = runTest(timeout = ShortTimeout) {
		assertThat(lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.On)

		bluetoothEventsFake.emitBluetoothState(BluetoothAdapter.STATE_OFF)
		lapisBt.state.first { it == LapisBt.BluetoothState.Off }
		assertThat(lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.Off)

		bluetoothEventsFake.emitBluetoothState(BluetoothAdapter.STATE_TURNING_OFF)
		lapisBt.state.first { it == LapisBt.BluetoothState.TurningOff }
		assertThat(lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.TurningOff)

		bluetoothEventsFake.emitBluetoothState(BluetoothAdapter.STATE_ON)
		lapisBt.state.first { it == LapisBt.BluetoothState.On }
		assertThat(lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.On)

		bluetoothEventsFake.emitBluetoothState(BluetoothAdapter.STATE_TURNING_ON)
		lapisBt.state.first { it == LapisBt.BluetoothState.TurningOn }
		assertThat(lapisBt.state.value).isEqualTo(LapisBt.BluetoothState.TurningOn)
	}


	companion object {
		private val ShortTimeout = 2.seconds
	}
}
