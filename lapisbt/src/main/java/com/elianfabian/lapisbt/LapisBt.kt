package com.elianfabian.lapisbt

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothAdapterImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothEventsImpl
import com.elianfabian.lapisbt.model.BluetoothDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

public interface LapisBt {

	public val pairedDevices: StateFlow<List<BluetoothDevice>>

	public val scannedDevices: StateFlow<List<BluetoothDevice>>

	public val events: SharedFlow<Event>

	public val bluetoothDeviceName: StateFlow<String?>

	public val isBluetoothSupported: Boolean

	// Internally this checks for the bluetooth connect permission, we should think if this
	// property actually makes sense, or we should name it a different way or whatever
	public val canEnableBluetooth: Boolean

	public val state: StateFlow<BluetoothState>

	public val isScanning: StateFlow<Boolean>

	public val activeBluetoothServersUuids: StateFlow<List<UUID>>

	// I don't think we should define this, every client of this library should treat this their own way
//	val canChangeBluetoothDeviceName: Boolean


	public fun setBluetoothDeviceName(newName: String): Boolean

	public fun startScan(): Boolean

	public fun stopScan(): Boolean

	public fun clearScannedDevices()

	public suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): ConnectionResult

	public suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): ConnectionResult

	public fun stopBluetoothServer(serviceUuid: UUID)

	public suspend fun connectToDevice(deviceAddress: String, serviceUuid: UUID): ConnectionResult

	public suspend fun connectToDeviceWithoutPairing(deviceAddress: String, serviceUuid: UUID): ConnectionResult

	public suspend fun disconnectFromDevice(deviceAddress: String): Boolean

	public suspend fun cancelConnectionAttempt(deviceAddress: String): Boolean

	public fun getRemoteDevice(deviceAddress: String): BluetoothDevice?

	public fun pairDevice(deviceAddress: String): Boolean

	// I'm not sure if we should keep this since this access an internal API
	public fun unpairDevice(deviceAddress: String): Boolean

	public suspend fun sendData(deviceAddress: String, action: suspend (stream: OutputStream) -> Unit): Boolean

	public suspend fun receiveData(deviceAddress: String, action: suspend (stream: InputStream) -> Unit): Boolean


	public fun dispose()


	public enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		public val isOn: Boolean get() = this == On
	}

	public sealed interface ConnectionResult {
		public data class ConnectionEstablished(val device: BluetoothDevice) : ConnectionResult
		public data object CouldNotConnect : ConnectionResult
	}

	public sealed interface Event {
		public data class OnDeviceConnected(
			val connectedDevice: BluetoothDevice,
			// This indicates whether you connected to a device as a server or intentionally chose which one to connect to
			val manuallyConnected: Boolean,
		) : Event

		public data class OnDeviceDisconnected(
			val disconnectedDevice: BluetoothDevice,
			// This indicates if was the current user who intentionally disconnected the device
			// In the case the user intentionally disconnects from the device but it was the other device
			// who disconnected from us it will count as not manually disconnected
			val manuallyDisconnected: Boolean,
		) : Event

		public data class OnDeviceScanned(val scannedDevice: BluetoothDevice) : Event
	}


	public companion object {
		public fun newInstance(context: Context): LapisBt {
			val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

			return LapisBtImpl(
				lapisAdapter = LapisBluetoothAdapterImpl(bluetoothManager.adapter),
				androidHelper = AndroidHelperImpl(context),
				bluetoothEvents = LapisBluetoothEventsImpl(context),
			)
		}
	}
}
