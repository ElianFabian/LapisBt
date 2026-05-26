package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice

public class FakeBluetoothDevice internal constructor(
    public val address: BluetoothDevice.Address,
    public val lapisBt: LapisBt,
    public val config: FakeBluetoothConfiguration,
    private val events: LapisBluetoothEventsFake,
) {
    public val name: String? get() = lapisBt.bluetoothDeviceName.value

    public fun setBluetoothState(newState: LapisBt.BluetoothState) {
        config.bluetoothState = newState
        val androidState = when (newState) {
            LapisBt.BluetoothState.On -> BluetoothAdapter.STATE_ON
            LapisBt.BluetoothState.TurningOn -> BluetoothAdapter.STATE_TURNING_ON
            LapisBt.BluetoothState.Off -> BluetoothAdapter.STATE_OFF
            LapisBt.BluetoothState.TurningOff -> BluetoothAdapter.STATE_TURNING_OFF
        }
        events.emitBluetoothState(androidState)
    }

    public fun setPermissions(connect: Boolean, scan: Boolean) {
        config.isBluetoothConnectGranted = connect
        config.isBluetoothScanGranted = scan
        // In a real app, resuming an activity often triggers permission checks
        events.emitActivityResumed()
    }

    public fun setLocationEnabled(enabled: Boolean) {
        config.isLocationEnabled = enabled
        events.emitActivityResumed()
    }

    internal fun onActivityResumed() {
        events.emitActivityResumed()
    }
}
