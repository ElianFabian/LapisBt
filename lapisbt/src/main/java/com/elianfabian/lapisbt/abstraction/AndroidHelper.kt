package com.elianfabian.lapisbt.abstraction

internal interface AndroidHelper {

	fun getApiLevel(): Int
	fun isBluetoothClassicSupported(): Boolean
	fun isBluetoothConnectGranted(): Boolean
	fun isBluetoothScanGranted(): Boolean
	fun isAccessFineLocationGranted(): Boolean
	fun isAccessCoarseLocationGranted(): Boolean
	fun isLocationEnabled(): Boolean
}
