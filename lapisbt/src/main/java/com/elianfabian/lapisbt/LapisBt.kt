package com.elianfabian.lapisbt

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothAdapterImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothEventsImpl
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.model.BluetoothDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

public interface LapisBt {

	public val pairedDevices: StateFlow<List<BluetoothDevice>>

	public val scannedDevices: StateFlow<List<BluetoothDevice>>

	// This state is useful to show the connected devices that come from direct connections
	// (a connection made by knowing the address without the device being paired or scanned)
	public val connectedDevices: StateFlow<List<BluetoothDevice>>

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

	@InternalBluetoothReflectionApi
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

		public data class OnPairingRequest(
			val device: BluetoothDevice,
			val pairingKey: Int,
			val pairingVariant: PairingVariant,
		) : Event {
			public enum class PairingVariant {
				Pin,
				PasskeyConfirmation,
			}
		}
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

		/**
		 * Validate a String Bluetooth address, such as "00:43:A8:23:10:F0"
		 *
		 *
		 * Alphabetic characters must be uppercase to be valid.
		 *
		 * @param address Bluetooth address as string
		 * @return true if the address is valid, false otherwise
		 */
		public fun checkBluetoothAddress(address: String?): Boolean {
			val addressLength = 17

			if (address == null || address.length != addressLength) {
				return false
			}
			for (i in 0..<addressLength) {
				val c = address[i]
				when (i % 3) {
					0, 1 -> {
						if ((c in '0'..'9') || (c in 'A'..'F')) {
							// hex character, OK
							break
						}
						return false
					}
					2 -> {
						if (c == ':') {
							break // OK
						}
						return false
					}
				}
			}
			return true
		}
	}
}
