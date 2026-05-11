package com.elianfabian.lapisbt.app.common.domain

import kotlinx.coroutines.flow.Flow

interface AndroidHelper {

	fun stopApplication()
	fun showToast(message: String)

	fun openAppSettings()
	fun openBluetoothSettings()
	fun openDeviceInfoSettings()

	// This function will also enable bluetooth if is necessary
	suspend fun showMakeDeviceDiscoverableDialog(seconds: Int = 60): Boolean
	suspend fun showEnableBluetoothDialog(): Boolean
	suspend fun showEnableLocationDialog(): Boolean

	fun closeKeyboard()

	fun isAppInBackground(): Boolean
	fun isAppClosed(): Boolean

	fun brightnessFlow(): Flow<Int>

	fun lightSensorFlow(): Flow<Float>
}
