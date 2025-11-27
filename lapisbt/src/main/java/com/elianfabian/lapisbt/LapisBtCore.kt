package com.elianfabian.lapisbt

import android.content.Context
import com.elianfabian.lapisbt.model.BluetoothDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface LapisBtCore {

	val devices: StateFlow<List<BluetoothDevice>>

	val scannedDevices: SharedFlow<BluetoothDevice>

	val events: SharedFlow<Event>

	val bluetoothDeviceName: StateFlow<String?>

	val isBluetoothSupported: Boolean

	val canEnableBluetooth: Boolean

	val state: StateFlow<BluetoothState>

	val isScanning: StateFlow<Boolean>

	val activeBluetoothServers: StateFlow<List<UUID>>

	// I don't think we should define this, every client of this library should treat this their own way
//	val canChangeBluetoothDeviceName: Boolean


	fun setBluetoothDeviceName(newName: String): Boolean

	fun startScan(): Boolean

	fun stopScan(): Boolean

	suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): ConnectionResult

	suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): ConnectionResult

	fun stopBluetoothServer(serviceUuid: UUID)

	suspend fun connectToDevice(deviceAddress: String, serviceUuid: UUID): ConnectionResult

	suspend fun connectToDeviceWithoutPairing(deviceAddress: String, serviceUuid: UUID): ConnectionResult

	suspend fun disconnectFromDevice(deviceAddress: String): Boolean

	suspend fun cancelConnectionAttempt(deviceAddress: String): Boolean

	// I'm not sure if we should keep this since this access an internal API
	fun unpairDevice(deviceAddress: String): Boolean


	// TODO: We have to think of a proper way to implement data communication, probably just using streams
//	suspend fun sendDataToDevice(deviceAddress: String, data: ByteArray): Boolean
//
//	fun observeDataFromDevice(deviceAddress: String): Flow<ByteArray>


	fun dispose()


	enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		val isOn: Boolean get() = this == On
	}

	sealed interface ConnectionResult {
		data class ConnectionEstablished(val device: BluetoothDevice) : ConnectionResult
		data object CouldNotConnect : ConnectionResult
	}

	sealed interface Event {
		data class OnDeviceConnected(
			val connectedDevice: BluetoothDevice,
			// This indicates whether you connected to a device as a server or intentionally chose which one to connect to
			val manuallyConnected: Boolean,
		) : Event

		data class OnDeviceDisconnected(
			val disconnectedDevice: BluetoothDevice,
			// This indicates if was the current user who intentionally disconnected the device
			// In the case the user intentionally disconnects from the device but it was the other device
			// who disconnected from us it will count as not manually disconnected
			val manuallyDisconnected: Boolean,
		) : Event
	}


	companion object {
		fun newInstance(context: Context): LapisBtCore = LapisBtCoreImpl(
			context = context,
		)
	}
}
