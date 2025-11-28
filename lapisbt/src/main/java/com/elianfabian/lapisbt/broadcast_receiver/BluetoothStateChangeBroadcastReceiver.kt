package com.elianfabian.lapisbt.broadcast_receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class BluetoothStateChangeBroadcastReceiver(
	private val onStateChange: (state: Int) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
			return
		}
		val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
		onStateChange(state)
	}
}
