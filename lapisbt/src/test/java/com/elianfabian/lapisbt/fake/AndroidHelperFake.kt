package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.AndroidHelper

class AndroidHelperFake(
	var isBluetoothSupportedResult: Boolean,
	var isBluetoothConnectGrantedResult: Boolean,
	var isBluetoothScanGrantedResult: Boolean,
) : AndroidHelper {

	override fun isBluetoothSupported(): Boolean {
		return isBluetoothSupportedResult
	}

	override fun isBluetoothConnectGranted(): Boolean {
		return isBluetoothConnectGrantedResult
	}

	override fun isBluetoothScanGranted(): Boolean {
		return isBluetoothScanGrantedResult
	}
}
