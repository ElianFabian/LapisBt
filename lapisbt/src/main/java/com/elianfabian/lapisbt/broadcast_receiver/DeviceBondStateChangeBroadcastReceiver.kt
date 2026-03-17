package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

internal class DeviceBondStateChangeBroadcastReceiver(
	private val onStateChange: (device: AndroidBluetoothDevice, oldState: Int, newState: Int, reason: Int) -> Unit,
) : BroadcastReceiver() {

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED) {
			return
		}

		println("$$$ DeviceBondStateChangeBroadcastReceiver.intent: ${intent.contentToString()}")

		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		val oldState = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, AndroidBluetoothDevice.ERROR)
		val newState = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_BOND_STATE, AndroidBluetoothDevice.ERROR)
		val reason = intent.getIntExtra(AndroidInternalConstantans.EXTRA_UNBOND_REASON, 0)

		onStateChange(device, oldState, newState, reason)
	}


	private object AndroidInternalConstantans {
		const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
	}
}
