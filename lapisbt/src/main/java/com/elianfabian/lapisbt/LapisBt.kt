package com.elianfabian.lapisbt

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothAdapterImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothEventsImpl
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.annotation.NotReliableBluetoothApi
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

	public fun getRemoteDevice(deviceAddress: String): BluetoothDevice

	public fun pairDevice(deviceAddress: String): Boolean

	@InternalBluetoothReflectionApi
	public fun unpairDevice(deviceAddress: String): Boolean

	@InternalBluetoothReflectionApi
	public fun cancelPairingAttempt(deviceAddress: String): Boolean

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
			val connectedLocally: Boolean,
		) : Event

		public data class OnDeviceDisconnected(
			val disconnectedDevice: BluetoothDevice,
			// This indicates if was the current user who intentionally disconnected the device
			// In the case the user intentionally disconnects from the device but it was the other device
			// who disconnected from us it will count as not manually disconnected
			val disconnectedLocally: Boolean,
		) : Event

		public data class OnDeviceScanned(val scannedDevice: BluetoothDevice) : Event

		public data class OnPairingRequest(
			val device: BluetoothDevice,
			val pairingKey: Int,
			val pairingVariant: PairingVariant,
			val initiatedLocally: Boolean,
		) : Event {
			public enum class PairingVariant {
				Pin,
				PasskeyConfirmation,

				// These are internal values (during testing I accidentally got the 'consent' pairing variant,
				// so this is why we're adding them just in case
				Consent,
				DisplayPasskey,
				DisplayPin,
				OobConsent,
				Pin16Digits,
			}
		}

		// This event is based on internal APIs, so this may not work as expected.
		// Use it at your own risk.
		// This event might be removed in future versions of this library.
		// NOTES:
		// - Value AuthFailed (1) was received for both pairing dialog timeout and
		// when the device which initiated the pairing canceled the process.
		// - Value RemoteDeviceDown (4) was received by a device which tried to pair with another
		// but the pairing just randomly failed, the dialog didn't even show up.
		// - Value Removed (9) was received by the remote device when it canceled the request and also
		// when a device unbonded another device.
		@NotReliableBluetoothApi
		public data class OnPairingFailed(
			val device: BluetoothDevice,
			val reason: Reason,
		) : Event {
			public enum class Reason {
				AuthFailed,
				AuthRejected,
				AuthCanceled,
				RemoteDeviceDown,
				DiscoveryInProgress,
				AuthTimeout,
				RepeatedAttempts,
				RemoteAuthCanceled,
				Removed,
			}
		}

		// This is event is triggered when a device that didn't try to pair with us
		// appears now as bonded
		// So if this event is ever triggered is probably due to a Bluetooth stack bug
		public data class OnUnexpectedDevicePaired(
			val device: BluetoothDevice,
		) : Event
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
