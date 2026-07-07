package com.elianfabian.lapisbt.abstraction.impl

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.elianfabian.lapisbt.abstraction.AndroidHelper

@SuppressLint("NewApi")
internal class AndroidHelperImpl(
	private val context: Context,
) : AndroidHelper {

	override fun getApiLevel(): Int {
		return getApiLevelInternal()
	}

	override fun isBluetoothClassicSupported(): Boolean {
		return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
	}

	override fun isBluetoothConnectGranted(): Boolean {
		if (getApiLevelInternal() >= 31) {
			return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isBluetoothScanGranted(): Boolean {
		if (getApiLevelInternal() >= 31) {
			return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isAccessFineLocationGranted(): Boolean {
		if (getApiLevelInternal() >= 23) {
			return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isAccessCoarseLocationGranted(): Boolean {
		if (getApiLevelInternal() >= 23) {
			return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isAccessBackgroundLocationGranted(): Boolean {
		if (getApiLevelInternal() >= 29) {
			return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
		}
		return true
	}

	override fun isLocationEnabled(): Boolean {
		val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
		return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
			locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
	}

	@SuppressLint("InlinedApi")
	override fun isProcessReadyForClassicScan(): Boolean {
		val processInfo = ActivityManager.RunningAppProcessInfo()
		ActivityManager.getMyMemoryState(processInfo)

		val importance = processInfo.importance
		return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
			|| importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
	}


	private fun getApiLevelInternal(): Int {
		return Build.VERSION.SDK_INT
	}
}
