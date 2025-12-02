package com.elianfabian.lapisbt.app.common.domain

interface StorageController {

	fun getBluetoothAddress(): String?

	fun setBluetoothAddress(value: String?)
}
