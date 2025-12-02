package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

internal class DeviceConnectionStateChangeBroadcastReceiver(
	private val onConnectionStateChange: (device: AndroidBluetoothDevice, isConnected: Boolean) -> Unit,
) : BroadcastReceiver() {

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		when (intent.action) {
			AndroidBluetoothDevice.ACTION_ACL_CONNECTED -> {
				onConnectionStateChange(device, true)
			}
			AndroidBluetoothDevice.ACTION_ACL_DISCONNECTED -> {
				onConnectionStateChange(device, false)
			}
		}
	}
}
