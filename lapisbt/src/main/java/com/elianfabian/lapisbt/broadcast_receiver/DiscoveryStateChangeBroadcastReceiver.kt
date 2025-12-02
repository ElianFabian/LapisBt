package com.elianfabian.lapisbt.broadcast_receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class DiscoveryStateChangeBroadcastReceiver(
	private val onDiscoveryStateChange: (isDiscovering: Boolean) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
				onDiscoveryStateChange(true)
			}
			BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
				onDiscoveryStateChange(false)
			}
		}
	}
}
