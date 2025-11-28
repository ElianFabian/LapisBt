package com.elianfabian.bluetoothchatapp_prototype.common.domain

interface AndroidHelper {

	fun stopApplication()
	fun showToast(message: String)

	fun openAppSettings()
	fun openBluetoothSettings()
	fun openDeviceInfoSettings()

	suspend fun showMakeDeviceDiscoverableDialog(seconds: Int = 60): Boolean
	suspend fun showEnableBluetoothDialog(): Boolean
	suspend fun showEnableLocationDialog(): Boolean

	fun closeKeyboard()

	fun isAppInBackground(): Boolean
	fun isAppClosed(): Boolean
}
