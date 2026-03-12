package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

public class PairingRequestBroadcastReceiver(
	private val onPairingRequest: (androidDevice: AndroidBluetoothDevice, pairingKey: Int, pairingVariant: Int) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != AndroidBluetoothDevice.ACTION_PAIRING_REQUEST) {
			return
		}

		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		val pairingKey = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PAIRING_KEY, AndroidBluetoothDevice.ERROR)
		val pairingVariant = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PAIRING_VARIANT, AndroidBluetoothDevice.ERROR)

		onPairingRequest(device, pairingKey, pairingVariant)
	}
}
