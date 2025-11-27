package com.elianfabian.lapisfit.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisfit.util.AndroidBluetoothDevice

class DeviceFoundBroadcastReceiver(
	private val onDeviceFound: (device: AndroidBluetoothDevice) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == AndroidBluetoothDevice.ACTION_FOUND) {
			val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE)
			if (device != null) {
				onDeviceFound(device)
			}
		}
	}
}
