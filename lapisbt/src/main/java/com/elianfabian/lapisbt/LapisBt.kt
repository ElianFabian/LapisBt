package com.elianfabian.lapisbt

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothAdapterImpl
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothEventsImpl
import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import com.elianfabian.lapisbt.annotation.NotReliableBluetoothApi
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothConfiguration
import com.elianfabian.lapisbt.simulated.SimulatedBluetoothEnvironment
import com.elianfabian.lapisbt.common.util.LapisLogConfig
import com.elianfabian.lapisbt.common.util.LapisLogger
import com.elianfabian.lapisbt.util.checkBluetoothAddressInternal
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * A high-level, "sanitized" abstraction for Bluetooth Classic on Android.
 *
 * Unlike the standard Android Bluetooth APIs, which often emit inconsistent states
 * or unreliable lifecycle broadcasts, [LapisBt] acts as a synchronization layer.
 * It internally validates connection health and bonding states to ensure that
 * the provided states, and events streams represent the actual hardware
 * reality, not just the stack's reported state.
 */
public interface LapisBt {

	public val state: StateFlow<BluetoothState>

	public val events: SharedFlow<Event>

	public val pairedDevices: StateFlow<List<BluetoothDevice>>

	public val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>

	// TODO: On Android we can only have 7 connected devices at once, maybe we could try to warn about it somehow
	// TODO: maybe we could indicate which UUID is used for every connection
	public val connectedDevices: StateFlow<List<BluetoothDevice>>

	public val bluetoothDeviceName: StateFlow<String?>

	public val isBluetoothClassicSupported: Boolean

	public val isScanning: StateFlow<Boolean>

	public val scanMode: StateFlow<ScanMode>

	public val activeBluetoothServersUuids: StateFlow<List<UUID>>

	public val logConfig: LapisLogConfig


	/**
	 * Sets the bluetooth name of the device.
	 *
	 * NOTES:
	 * - This doesn't work for all devices
	 * - For devices that don't support this you will see that it apparently works
	 * but when you go to bluetooth settings and go back to the app you will see
	 * the previous name, it seems there's no reliable way to detect this.
	 */
	public fun setBluetoothDeviceName(newName: String): Boolean

	/**
	 * Starts scanning devices.
	 *
	 * Device scanning is a heavyweight procedure. New connections to remote Bluetooth devices
	 * should not be attempted while scanning is in progress, and existing connections will
	 * experience limited bandwidth and high latency.
	 *
	 * Use [stopScan] to cancel an ongoing scanning.
	 *
	 * NOTES:
	 * - For some devices if this returns false it may be because it needs to enable the location
	 * in order to work.
	 *
	 * @see stopScan
	 */
	public fun startScan(): Boolean

	/**
	 * Cancel the current scanning process.
	 *
	 * Because discovery is a heavyweight procedure for the Bluetooth adapter, this method should
	 * always be called before attempting to connect to a remote device with [connectToDevice] or [connectToDeviceWithoutPairing].
	 *
	 * @return true on success, false on error.
	 *
	 * @see startScan
	 */
	public fun stopScan(): Boolean

	/**
	 * Clears the [scannedDevices] state.
	 */
	public fun clearScannedDevices()

	/**
	 * Starts a bluetooth server to wait for a connection.
	 *
	 * @param serviceName is an arbitrary name.
	 * @param serviceUuid can be any UUID you want, it has to match with the one
	 * used by the device who wants to connect with us.
	 *
	 * @see connectToDevice
	 * @see stopBluetoothServer
	 */
	public suspend fun startBluetoothServer(serviceName: String, serviceUuid: UUID): ConnectionResult

	/**
	 * Starts a bluetooth server to wait for a connection without requiring pairing.
	 * This will only work if the other device connects with us by calling [connectToDeviceWithoutPairing]
	 *
	 * @param serviceName is an arbitrary name.
	 * @param serviceUuid can be any UUID you want, it has to match with the one
	 * used by the device who wants to connect with us.
	 *
	 * @see connectToDeviceWithoutPairing
	 * @see stopBluetoothServer
	 */
	public suspend fun startBluetoothServerWithoutPairing(serviceName: String, serviceUuid: UUID): ConnectionResult

	/**
	 * Stops waiting for a connection for a given serviceUuid.
	 *
	 * @throws IllegalStateException if there's no such service uuid.
	 */
	public fun stopBluetoothServer(serviceUuid: UUID)

	/**
	 * Tries to connect with the given device and service uuid.
	 *
	 * NOTES:
	 * - Sometimes even when everything is okay the connection attempt will fail, in that case
	 * just try calling the function again.
	 *
	 * @see startBluetoothServer
	 * @see disconnectFromDevice
	 * @see cancelConnectionAttempt
	 */
	public suspend fun connectToDevice(deviceAddress: BluetoothDevice.Address, serviceUuid: UUID): ConnectionResult

	/**
	 * Tries to connect with the given device and service uuid without pairing.
	 *
	 * This will only work if the other device connects with us by calling startBluetoothServerWithoutPairing(...).
	 *
	 * NOTES:
	 * - Sometimes even when everything is okay the connection attempt will fail, in that case
	 * just try calling the function again.
	 *
	 * @see startBluetoothServerWithoutPairing
	 * @see disconnectFromDevice
	 * @see cancelConnectionAttempt
	 */
	public suspend fun connectToDeviceWithoutPairing(deviceAddress: BluetoothDevice.Address, serviceUuid: UUID): ConnectionResult

	/**
	 * Disconnects from the remote device.
	 *
	 * @return true on success, false on error.
	 */
	public suspend fun disconnectFromDevice(deviceAddress: BluetoothDevice.Address): Boolean

	/**
	 * Cancels an ongoing connection attempt before it has been established.
	 *
	 * @return true on success, false on error.
	 *
	 * @see connectToDevice
	 * @see connectToDeviceWithoutPairing
	 */
	public suspend fun cancelConnectionAttempt(deviceAddress: BluetoothDevice.Address): Boolean

	/**
	 * Returns a BluetoothDevice for the given [deviceAddress].
	 *
	 * It will first check the internal state of processed bluetooth devices
	 * to find it, otherwise it will call [android.bluetooth.BluetoothAdapter.getRemoteDevice].
	 */
	public fun getRemoteDevice(deviceAddress: BluetoothDevice.Address): BluetoothDevice

	/**
	 * Starts a pairing request with a device.
	 *
	 * NOTES:
	 * - Calling this function will force the disconnection with the given device.
	 *
	 * @return false on immediate error, true if pairing will begin.
	 */
	public fun startDevicePairing(deviceAddress: BluetoothDevice.Address): Boolean

	/**
	 * Unpairs a device.
	 *
	 * Calling this function will force the disconnection with the given device.
	 *
	 * @return true on success, false on error.
	 *
	 * @see startDevicePairing
	 */
	@InternalBluetoothReflectionApi
	public fun unpairDevice(deviceAddress: BluetoothDevice.Address): Boolean

	/**
	 * Cancel a current pairing attempt for the given device.
	 *
	 * @return true on success, false on error
	 *
	 * @see startDevicePairing
	 */
	@InternalBluetoothReflectionApi
	public fun cancelPairingAttempt(deviceAddress: BluetoothDevice.Address): Boolean

	/**
	 * Sends binary data to the target device via an [OutputStream].
	 *
	 * Execution is serialized per [deviceAddress]. If a write operation is already
	 * in progress for the specified device, subsequent calls will suspend until
	 * the previous operation completes.
	 *
	 * @param action A lambda providing access to the [OutputStream].
	 * @return True if the data was sent successfully, false otherwise.
	 */
	public suspend fun sendData(deviceAddress: BluetoothDevice.Address, action: suspend (stream: OutputStream) -> Unit): Boolean

	/**
	 * Receives binary data from the target device via an [InputStream].
	 *
	 * Execution is serialized per [deviceAddress]. If a read operation is already
	 * in progress for the specified device, subsequent calls will suspend and
	 * execute sequentially.
	 *
	 * @param action A lambda providing access to the [InputStream].
	 * @return True if the operation completed without exceptions, false otherwise.
	 */
	public suspend fun receiveData(deviceAddress: BluetoothDevice.Address, action: suspend (stream: InputStream) -> Unit): Boolean

	/**
	 * Performs a service discovery on the remote device via SDP (Service Discovery Protocol)
	 * to fetch its supported UUIDs.
	 *
	 * This function suspends until the discovery process completes, times out, or fails to initiate.
	 *
	 * **Side Effects:**
	 * * Successful discoveries will automatically update the internal caches for paired,
	 * scanned, and connected device flows.
	 *
	 * @param deviceAddress The Bluetooth address of the target device.
	 * @return A list of discovered [UUID]s. Returns an **empty list** if the discovery succeeds
	 * but zero services are exposed. Returns **null** if the operation times out or the hardware
	 * fails to initiate the request.
	 */
	public suspend fun getUuidsWithSdp(deviceAddress: BluetoothDevice.Address): List<UUID>?

	/**
	 * Releases all resources held by this instance.
	 *
	 * Once called, this instance is no longer usable.
	 */
	public fun dispose()


	public enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		public val isOn: Boolean get() = this == On
	}

	public enum class ScanMode {
		ConnectableDiscoverable,
		Connectable,
		None,
	}

	public sealed interface ConnectionResult {

		public data class ConnectionEstablished(val device: BluetoothDevice) : ConnectionResult
		public data object CouldNotConnect : ConnectionResult
	}

	public sealed interface Event {

		public val device: BluetoothDevice


		public data class OnDeviceConnected(
			override val device: BluetoothDevice,
			// This indicates whether you connected to a device as a server or intentionally chose which one to connect to
			val connectedLocally: Boolean,
		) : Event

		public data class OnDeviceDisconnected(
			override val device: BluetoothDevice,
			// This indicates if was the current user who intentionally disconnected the device
			// In the case the user intentionally disconnects from the device, but it was the other device
			// who disconnected from us, it will count as not manually disconnected
			public val disconnectedLocally: Boolean,
		) : Event

		public data class OnDeviceScanned(
			override val device: BluetoothDevice,
			val rssi: Short,
		) : Event

		public data class OnPairingRequest(
			override val device: BluetoothDevice,
			val pairingKey: Int,
			val pairingVariant: PairingVariant,
			val initiatedLocally: Boolean,
		) : Event {
			public enum class PairingVariant {
				Pin,
				PasskeyConfirmation,

				// These are internal values (during testing we accidentally got the 'consent' pairing variant,
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
		public data class OnPairingFailed(
			override val device: BluetoothDevice,

			@NotReliableBluetoothApi
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

		/**
		 * Triggered when a device reports a bonded state despite no active pairing attempt.
		 *
		 * This usually indicates a Bluetooth stack bug resulting in "asymmetric pairing"
		 * (we see them as paired, but they do not see us as paired).
		 *
		 * Impact:
		 * * The device will incorrectly appear in scan results (paired devices normally don't).
		 * * Subsequent connection/pairing attempts will likely fail.
		 */
		public data class OnUnexpectedDevicePaired(
			override val device: BluetoothDevice,
		) : Event
	}


	public companion object {

		/**
		 * Creates a new instance of [LapisBt].
		 *
		 * To ensure state consistency, you should maintain a single instance of this interface
		 * throughout your application's lifecycle.
		 */
		public fun newInstance(
			context: Context,
			logger: LapisLogger = LapisLogger.Silent,
		): LapisBt {
			val appContext = context.applicationContext
			val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

			return LapisBtImpl(
				lapisAdapter = LapisBluetoothAdapterImpl(bluetoothManager.adapter),
				androidHelper = AndroidHelperImpl(appContext),
				bluetoothEvents = LapisBluetoothEventsImpl(
					context = appContext,
					logger = logger,
				),
				logger = logger,
			)
		}

		public fun newSimulatedBluetoothEnvironment(
			seed: Long = 1L,
			context: Context? = null,
			globalConfig: SimulatedBluetoothConfiguration = SimulatedBluetoothConfiguration(),
			createLogger: (deviceAddress: BluetoothDevice.Address) -> LapisLogger = { LapisLogger.console() },
		): SimulatedBluetoothEnvironment {
			return SimulatedBluetoothEnvironment(
				context = context,
				seed = seed,
				globalConfig = globalConfig,
				createLogger = createLogger,
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
			return checkBluetoothAddressInternal(address)
		}
	}
}
