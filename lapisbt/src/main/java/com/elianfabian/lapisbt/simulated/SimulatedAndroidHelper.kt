package com.elianfabian.lapisbt.simulated

import android.content.Context
import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl

internal class SimulatedAndroidHelper(
	private val config: SimulatedBluetoothConfiguration,
	context: Context?,
) : AndroidHelper {

	private val realHelper: AndroidHelper? = context?.let { AndroidHelperImpl(it) }

	override fun getApiLevel(): Int {
		return realHelper?.getApiLevel() ?: config.apiLevel
	}

	override fun isBluetoothClassicSupported(): Boolean {
		return realHelper?.isBluetoothClassicSupported() ?: config.isBluetoothSupported
	}

	override fun isBluetoothConnectGranted(): Boolean {
		return realHelper?.isBluetoothConnectGranted() ?: config.isBluetoothConnectGranted
	}

	override fun isBluetoothScanGranted(): Boolean {
		return realHelper?.isBluetoothScanGranted() ?: config.isBluetoothScanGranted
	}

	override fun isAccessFineLocationGranted(): Boolean {
		return realHelper?.isAccessFineLocationGranted() ?: config.isAccessFineLocationGranted
	}

	override fun isAccessCoarseLocationGranted(): Boolean {
		return realHelper?.isAccessCoarseLocationGranted() ?: config.isAccessCoarseLocationGranted
	}

	override fun isLocationEnabled(): Boolean {
		return realHelper?.isLocationEnabled() ?: config.isLocationEnabled
	}
}
