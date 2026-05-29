package com.elianfabian.lapisbt.simulated

import com.elianfabian.lapisbt.abstraction.AndroidHelper
import com.elianfabian.lapisbt.abstraction.impl.AndroidHelperImpl
import android.content.Context

internal class SimulatedAndroidHelper(
	private val config: SimulatedBluetoothConfiguration,
	private val context: Context?
) : AndroidHelper {

    private val realHelper: AndroidHelper? = context?.let { AndroidHelperImpl(it) }

	override fun isBluetoothClassicSupported(): Boolean {
		return realHelper?.isBluetoothClassicSupported() ?: config.isBluetoothSupported
	}

	override fun isBluetoothConnectGranted(): Boolean {
		return realHelper?.isBluetoothConnectGranted() ?: config.isBluetoothConnectGranted
	}

	override fun isBluetoothScanGranted(): Boolean {
		return realHelper?.isBluetoothScanGranted() ?: config.isBluetoothScanGranted
	}

	override fun isLocationEnabled(): Boolean {
		return realHelper?.isLocationEnabled() ?: config.isLocationEnabled
	}
}
