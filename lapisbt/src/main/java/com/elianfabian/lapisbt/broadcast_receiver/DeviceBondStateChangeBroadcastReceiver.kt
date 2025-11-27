package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

class DeviceBondStateChangeBroadcastReceiver(
	private val onStateChange: (device: AndroidBluetoothDevice, state: Int) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED) {
			return
		}

		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		val state = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_BOND_STATE, AndroidBluetoothDevice.ERROR)

		onStateChange(device, state)
	}
}
