package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice

class FakeBluetoothDevice(
    val address: BluetoothDevice.Address,
    val lapisBt: LapisBt
) {
    val name: String? get() = lapisBt.bluetoothDeviceName.value
}
