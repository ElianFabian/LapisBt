package com.elianfabian.lapisbt

import com.elianfabian.lapisbt.fake.AndroidHelperFake
import com.elianfabian.lapisbt.fake.LapisBluetoothAdapterFake
import com.elianfabian.lapisbt.fake.LapisBluetoothEventsFake
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

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
	fun `bluetooth support and permissions`() = runTest {
		assertThat(lapisBt.isBluetoothSupported).isTrue()
		androidHelperFake.isBluetoothSupportedResult = false
		assertThat(lapisBt.isBluetoothSupported).isFalse()

		assertThat(lapisBt.canEnableBluetooth).isTrue()
		androidHelperFake.isBluetoothConnectGrantedResult = false
		assertThat(lapisBt.canEnableBluetooth).isFalse()
	}

	@Test
	fun `start scan sets isScanning true`() = runTest {
		assertThat(lapisBt.startScan()).isTrue()
		lapisBt.isScanning.first { it }
		assertThat(lapisBt.isScanning.value).isTrue()
	}

	@Test
	fun `stop scan sets isScanning false`() = runTest {
		lapisBt.startScan()
		lapisBt.isScanning.first { it }

		assertThat(lapisBt.stopScan()).isTrue()
		lapisBt.isScanning.first { !it }
		assertThat(lapisBt.isScanning.value).isFalse()
	}

	@Test
	fun `scan fails when permission denied`() = runTest {
		androidHelperFake.isBluetoothScanGrantedResult = false

		assertThat(lapisBt.startScan()).isFalse()
		assertThat(lapisBt.stopScan()).isFalse()
	}
}
