package com.elianfabian.lapisbt_rpc.exception

public class DeviceNotConnectedException(deviceAddress: String) : RuntimeException(
	"Device '$deviceAddress' is not connected"
)
