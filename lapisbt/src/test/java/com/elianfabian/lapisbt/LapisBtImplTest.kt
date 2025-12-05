package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.fake.AndroidHelperFake
import com.elianfabian.lapisbt.fake.LapisBluetoothAdapterFake
import com.elianfabian.lapisbt.fake.LapisBluetoothEventsFake
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
			lapisBt.events.filterIsInstance<LapisBt.Event.OnDeviceScanned>()
				.map { it.scannedDevice }
				.collect { device ->
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

	@Test
	fun `bondedDevices list updates on new bond`() = runTest(timeout = ShortTimeout + ShortTimeout) {
		val androidScannedDevice = lapisAdapterFake.getScannableDevices().first()

		launch {
			lapisBt.pairedDevices.collect { devices ->
				println("$$$ Scanned devices: ${devices.map { "(name=${it.name}, address=${it.address}, pairingState=${it.pairingState})" }}")
				val targetDevice = devices.firstOrNull { it.address == androidScannedDevice.address } ?: return@collect
				println("$$$ Device address=${targetDevice.name}, pairingState=${targetDevice.pairingState}")
			}
		}

		assertThat(androidScannedDevice.bondState).isEqualTo(AndroidBluetoothDevice.BOND_NONE)

		androidScannedDevice.createBond()

//		lapisBt.devices.first { devices ->
//			devices.any { it.address == androidScannedDevice.address && it.pairingState == BluetoothDevice.PairingState.Pairing }
//		}
//		val scannedDevicePairing = lapisBt.devices.value.first { it.address == androidScannedDevice.address }
//		assertThat(scannedDevicePairing.pairingState).isEqualTo(BluetoothDevice.PairingState.Pairing)
//
//		lapisBt.devices.first { devices ->
//			devices.any { it.address == androidScannedDevice.address && it.pairingState == BluetoothDevice.PairingState.Paired }
//		}
//		val scannedDevicePaired = lapisBt.devices.value.first { it.address == androidScannedDevice.address }
//		assertThat(scannedDevicePaired.pairingState).isEqualTo(BluetoothDevice.PairingState.Paired)
	}


	companion object {
		private val ShortTimeout = 2.seconds
	}
}
