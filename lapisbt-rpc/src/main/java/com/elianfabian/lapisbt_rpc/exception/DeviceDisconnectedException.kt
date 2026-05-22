package com.elianfabian.lapisbt_rpc.exception

import com.elianfabian.lapisbt.model.BluetoothDevice

public class DeviceDisconnectedException(deviceAddress: BluetoothDevice.Address) : RuntimeException(
	"Device '$deviceAddress' got disconnected"
)
