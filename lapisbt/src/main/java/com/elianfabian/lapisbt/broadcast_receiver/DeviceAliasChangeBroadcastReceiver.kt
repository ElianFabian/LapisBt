package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

internal class DeviceAliasChangeBroadcastReceiver(
	private val onAliasChanged: (androidDevice: AndroidBluetoothDevice, newAlias: String?) -> Unit,
) : BroadcastReceiver() {

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		if (Build.VERSION.SDK_INT < 35) {
			return
		}
		if (intent.action != AndroidBluetoothDevice.ACTION_ALIAS_CHANGED) {
			return
		}

		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		val newAlias = device.alias

		onAliasChanged(device, newAlias)
	}
}
