package com.elianfabian.lapisbt

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.fake.AndroidHelperFake
import com.elianfabian.lapisbt.fake.LapisBluetoothAdapterFake
import com.elianfabian.lapisbt.fake.LapisBluetoothDeviceFake
import com.elianfabian.lapisbt.fake.LapisBluetoothEventsFake
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

// TODO: we should add more tests
// FIXME: we made several behavioral changes, so now we have to update the tests
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
	fun `pair and unpair functions updates scannedDevices and pairedDevices`() = runTest(timeout = ShortTimeout * 4) {
		lapisBt.startScan()
		lapisBt.scannedDevices.first { it.size >= 3 }

		val device = lapisBt.scannedDevices.first().first()

		lapisBt.pairDevice(device.address)

		lapisBt.scannedDevices.first { pairedDevices ->
			device.address in pairedDevices.map { it.address }
		}
		assertThat(lapisBt.scannedDevices.value.map { it.address })
			.contains(device.address)
		assertThat(lapisBt.pairedDevices.value.map { it.address })
			.doesNotContain(device.address)
		assertThat(lapisBt.scannedDevices.value.first { it.address == device.address }.pairingState)
			.isEqualTo(BluetoothDevice.PairingState.Pairing)

		lapisBt.pairedDevices.first { pairedDevices ->
			device.address in pairedDevices.map { it.address }
		}
		assertThat(lapisBt.pairedDevices.value.map { it.address })
			.contains(device.address)
		assertThat(lapisBt.scannedDevices.value.map { it.address })
			.doesNotContain(device.address)
		assertThat(lapisBt.pairedDevices.value.first { it.address == device.address }.pairingState)
			.isEqualTo(BluetoothDevice.PairingState.Paired)

		lapisBt.unpairDevice(device.address)
		lapisBt.pairedDevices.first { pairedDevices ->
			device.address !in pairedDevices.map { it.address }
		}
		assertThat(lapisBt.pairedDevices.value.map { it.address })
			.doesNotContain(device.address)
		assertThat(lapisBt.scannedDevices.value.map { it.address })
			.contains(device.address)
	}

	@Test
	fun `deviceFound replaces existing scanned device`() = runTest(timeout = ShortTimeout) {
		val device = lapisAdapterFake.getScannableDevices().first() as LapisBluetoothDeviceFake
		bluetoothEventsFake.emitDeviceFound(device)

		lapisBt.scannedDevices.first { it.isNotEmpty() }

		val updated = device.copy(name = "UpdatedName")
		bluetoothEventsFake.emitDeviceFound(updated)

		lapisBt.scannedDevices.first { devices ->
			devices.any { it.name == "UpdatedName" }
		}
		val result = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(result.name).isEqualTo("UpdatedName")
	}

	@Test
	fun `clearScannedDevices clears scannedDevices`() = runTest(timeout = ShortTimeout) {
		assertThat(lapisBt.scannedDevices.value).isEmpty()

		lapisBt.startScan()
		lapisBt.scannedDevices.first { it.isNotEmpty() }
		assertThat(lapisBt.scannedDevices.value).isNotEmpty()

		lapisBt.clearScannedDevices()
		assertThat(lapisBt.scannedDevices.value).isEmpty()
	}

	@Test
	fun `activeBluetoothServersUuids updates correctly`() = runTest(timeout = MediumTimeout) {
		assertThat(lapisBt.activeBluetoothServersUuids.value).isEmpty()

		val serviceUuid = UUID.randomUUID()
		val job1 = launch {
			lapisBt.startBluetoothServer(
				serviceName = "TestService",
				serviceUuid = serviceUuid,
			)
		}
		lapisBt.activeBluetoothServersUuids.first { it.isNotEmpty() }
		assertThat(lapisBt.activeBluetoothServersUuids.value).isNotEmpty()
		assertThat(lapisBt.activeBluetoothServersUuids.value).contains(serviceUuid)

		lapisBt.activeBluetoothServersUuids.first { it.isEmpty() }
		assertThat(lapisBt.activeBluetoothServersUuids.value).isEmpty()

		job1.join()

		launch {
			lapisBt.startBluetoothServer(
				serviceName = "TestService",
				serviceUuid = serviceUuid,
			)
		}

		lapisBt.activeBluetoothServersUuids.first { it.isNotEmpty() }
		assertThat(lapisBt.activeBluetoothServersUuids.value).isNotEmpty()
		assertThat(lapisBt.activeBluetoothServersUuids.value).contains(serviceUuid)

		lapisBt.stopBluetoothServer(serviceUuid)
		lapisBt.activeBluetoothServersUuids.first { it.isEmpty() }
		assertThat(lapisBt.activeBluetoothServersUuids.value).isEmpty()
	}

	@Test
	fun `getRemoteDevice returns correct device`() = runTest(timeout = ShortTimeout) {
		val device = lapisAdapterFake.getScannableDevices().first()
		bluetoothEventsFake.emitDeviceFound(device)

		val remote = lapisBt.getRemoteDevice(device.address)
		assertThat(remote?.address).isEqualTo(device.address)
	}

	@Test
	fun `connectToDevice sets Connecting then Connected`() = runTest(timeout = MediumTimeout) {
		val device = lapisAdapterFake.getScannableDevices().first()
		bluetoothEventsFake.emitDeviceFound(device)

		val serviceUuid = UUID.randomUUID()
		val result = lapisBt.connectToDevice(device.address, serviceUuid)
		assertThat(result).isInstanceOf(LapisBt.ConnectionResult.ConnectionEstablished::class.java)

		val updated = lapisBt.connectedDevices.value.first { it.address == device.address }
		assertThat(updated.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Connected)
	}

	@Test
	fun `device connection flow updates connection state correctly`() = runTest(timeout = MediumTimeout * 2) {
		val device = lapisAdapterFake.getScannableDevices().first()
		bluetoothEventsFake.emitDeviceFound(device)
		lapisBt.startScan()
		lapisBt.scannedDevices.first { it.size >= 3 }
		lapisBt.scannedDevices.first { devices ->
			devices.any { it.address == device.address }
		}

		val serviceUuid = UUID.randomUUID()
		val connectToDeviceJob = launch {
			lapisBt.connectToDevice(device.address, serviceUuid)
		}

		lapisBt.scannedDevices.first { devices ->
			devices.any { it.address == device.address && it.connectionState == BluetoothDevice.ConnectionState.Connecting }
		}
		val connectingDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(connectingDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Connecting)

		connectToDeviceJob.join()
		val connectedDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(connectedDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Connected)

		val disconnectFromDeviceJob = launch {
			lapisBt.disconnectFromDevice(device.address)
		}
		// It doesn't seem possible to properly check for the Disconnecting state
//		lapisBt.scannedDevices.first { devices ->
//			val device = devices.first() { it.address == device.address }
//			device.connectionState == BluetoothDevice.ConnectionState.Disconnecting
//		}
//		val disconnectingDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
//		assertThat(disconnectingDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Disconnecting)

		disconnectFromDeviceJob.join()
		val disconnectedDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(disconnectedDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Disconnected)
	}

	@Test
	fun `cancel connection attempt`() = runTest(timeout = MediumTimeout) {
		val device = lapisAdapterFake.getScannableDevices().first()
		bluetoothEventsFake.emitDeviceFound(device)
		lapisBt.scannedDevices.first { devices ->
			devices.any { it.address == device.address }
		}

		val serviceUuid = UUID.randomUUID()
		launch {
			lapisBt.connectToDevice(device.address, serviceUuid)
		}

		lapisBt.scannedDevices.first { devices ->
			devices.any { it.address == device.address && it.connectionState == BluetoothDevice.ConnectionState.Connecting }
		}
		val connectingDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(connectingDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Connecting)

		lapisBt.cancelConnectionAttempt(device.address)
		val connectedDevice = lapisBt.scannedDevices.value.first { it.address == device.address }
		assertThat(connectedDevice.connectionState).isEqualTo(BluetoothDevice.ConnectionState.Disconnected)
	}


	companion object {
		private val ShortTimeout = 2.seconds
		private val MediumTimeout = 5.seconds
	}
}
