package com.elianfabian.lapisbt

import com.elianfabian.lapisbt.fake.AndroidHelperFake
import com.elianfabian.lapisbt.fake.LapisBluetoothAdapterFake
import com.elianfabian.lapisbt.fake.LapisBluetoothEventsFake
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
	fun testExample() {
		assertThat(lapisBt.isBluetoothSupported).isTrue()

		androidHelperFake.isBluetoothSupportedResult = false

		assertThat(lapisBt.isBluetoothSupported).isFalse()

		assertThat(lapisBt.canEnableBluetooth).isTrue()

		androidHelperFake.isBluetoothConnectGrantedResult = false

		assertThat(lapisBt.canEnableBluetooth).isFalse()
	}

}
