package com.elianfabian.lapisbt.broadcast_receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class ScanModeChangeBroadcastReceiver(
	private val onScanModeChanged: (previousScanMode: Int, newScanMode: Int) -> Unit,
) : BroadcastReceiver() {

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != BluetoothAdapter.ACTION_SCAN_MODE_CHANGED) {
			return
		}

		val previousScanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE)
		val newScanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE)

		onScanModeChanged(previousScanMode, newScanMode)
	}
}
