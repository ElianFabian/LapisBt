package com.elianfabian.lapisbt_rpc.exception

import com.elianfabian.lapisbt.model.BluetoothDevice

public class DeviceNotConnectedException(deviceAddress: BluetoothDevice.Address) : RuntimeException(
	"Device '$deviceAddress' is not connected"
)
