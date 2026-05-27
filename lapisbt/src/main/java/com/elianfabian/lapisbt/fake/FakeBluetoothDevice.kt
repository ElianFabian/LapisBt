package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothAdapter
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

public class FakeBluetoothDevice internal constructor(
    public val address: BluetoothDevice.Address,
    public val lapisBt: LapisBt,
    public val config: FakeBluetoothConfiguration,
    private val events: LapisBluetoothEventsFake,
    private val environment: FakeBluetoothEnvironment,
) {
    public val name: String? get() = lapisBt.bluetoothDeviceName.value

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

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

    internal fun launchPairingProcess(targetAddress: String) {
        scope.launch {
            // Simulate pairing request delay
            delay(250)
            
            val pairingResult = config.pairingResult
            if (pairingResult is FakeBluetoothConfiguration.PairingResult.Failure) {
                events.emitUnbondReason(
                    LapisBluetoothEvents.UnbondReasonEvent(
                        androidDevice = environment.getScannableDevices(address.value).first { it.address == targetAddress },
                        reason = pairingResult.reason
                    )
                )
                return@launch
            }

            val myLapisDevice = environment.getScannableDevices(targetAddress).first { it.address == address.value }
            val targetLapisDevice = environment.getScannableDevices(address.value).first { it.address == targetAddress }
            val pairingKey = 123456
            val pairingVariant = AndroidBluetoothDevice.PAIRING_VARIANT_PIN

            // Emit the dialog event for both devices
            events.emitPairingRequestEvent(
                LapisBluetoothEvents.PairingRequestEvent(
                    androidDevice = targetLapisDevice,
                    pairingKey = pairingKey,
                    pairingVariant = pairingVariant
                )
            )

            environment.getDeviceEvents(targetAddress)?.emitPairingRequestEvent(
                LapisBluetoothEvents.PairingRequestEvent(
                    androidDevice = myLapisDevice,
                    pairingKey = pairingKey,
                    pairingVariant = pairingVariant
                )
            )

            // Simulate user interaction delay
            delay(500)
            
            environment.bondDevices(address.value, targetAddress)
        }
    }
}
