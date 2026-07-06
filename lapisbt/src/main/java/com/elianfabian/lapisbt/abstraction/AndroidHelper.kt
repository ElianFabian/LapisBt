package com.elianfabian.lapisbt.abstraction

internal interface AndroidHelper {

	fun isBluetoothClassicSupported(): Boolean

	fun isBluetoothConnectGranted(): Boolean
	fun isBluetoothScanGranted(): Boolean
	fun isAccessFineLocationGranted(): Boolean
	fun isAccessCoarseLocationGranted(): Boolean
	fun isAccessBackgroundLocationGranted(): Boolean
	fun isLocationEnabled(): Boolean
	fun isProcessReadyForClassicScan(): Boolean
}
