package com.elianfabian.lapisbt.abstraction

internal interface AndroidHelper {

	fun isBluetoothSupported(): Boolean

	fun isBluetoothConnectGranted(): Boolean
	fun isBluetoothScanGranted(): Boolean
}
