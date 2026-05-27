package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.LapisBt

/**
 * Configuration for a simulated Bluetooth device.
 *
 * In **UnitTest Mode** (when [FakeBluetoothEnvironment] has no context), all properties are used.
 * In **AppMock Mode** (when [FakeBluetoothEnvironment] has a context), global hardware properties
 * (like [bluetoothState] and [isBluetoothConnectGranted]) are ignored in favor of real OS state,
 * while [connectionResult] and [pairingResult] are still respected for simulated devices.
 */
public data class FakeBluetoothConfiguration(
    // Global Hardware State (Used in UnitTest Mode only)
    public var isBluetoothSupported: Boolean = true,
    public var bluetoothState: LapisBt.BluetoothState = LapisBt.BluetoothState.On,
    public var isBluetoothConnectGranted: Boolean = true,
    public var isBluetoothScanGranted: Boolean = true,
    public var isLocationEnabled: Boolean = true,
    public var name: String? = "Fake Adapter",
    public var needsLocationForScan: Boolean = false,
    public var scanMode: Int = BluetoothAdapter.SCAN_MODE_NONE,

    // Remote Device Interaction (Used in both modes)
    public var connectionResult: ConnectionResult = ConnectionResult.Success,
    public var pairingResult: PairingResult = PairingResult.Success,
) {
	public sealed interface ConnectionResult {
		public data object Success : ConnectionResult
		public data object CouldNotConnect : ConnectionResult
	}

	public sealed interface PairingResult {
		public data object Success : PairingResult
		public data class Failure(val reason: Int) : PairingResult
	}
}
