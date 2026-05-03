package com.elianfabian.lapisbt.abstraction.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.elianfabian.lapisbt.abstraction.AndroidHelper

internal class AndroidHelperImpl(
	private val context: Context,
) : AndroidHelper {

	override fun isBluetoothClassicSupported(): Boolean {
		return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
	}

	override fun isBluetoothConnectGranted(): Boolean {
		if (Build.VERSION.SDK_INT >= 31) {
			return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isBluetoothScanGranted(): Boolean {
		if (Build.VERSION.SDK_INT >= 31) {
			return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}
}
