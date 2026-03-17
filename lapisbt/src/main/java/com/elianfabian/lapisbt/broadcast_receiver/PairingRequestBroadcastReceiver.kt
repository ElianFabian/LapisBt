package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

public class PairingRequestBroadcastReceiver(
	private val onPairingRequest: (androidDevice: AndroidBluetoothDevice, pairingKey: Int, pairingVariant: Int) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != AndroidBluetoothDevice.ACTION_PAIRING_REQUEST) {
			return
		}

		println("$$$ PairingRequestBroadcastReceiver.intent: ${intent.contentToString()}")

		val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
		val pairingKey = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PAIRING_KEY, AndroidBluetoothDevice.ERROR)
		val pairingVariant = intent.getIntExtra(AndroidBluetoothDevice.EXTRA_PAIRING_VARIANT, AndroidBluetoothDevice.ERROR)

		onPairingRequest(device, pairingKey, pairingVariant)
	}
}


@Suppress("DEPRECATION")
internal fun Bundle.contentToString(): String {
	return "Bundle(${
		keySet().joinToString { key ->
			when (val value = get(key)) {
				is Bundle       -> "$key=${value.contentToString()}"
				is Array<*>     -> "$key=${value.contentToString()}"
				is IntArray     -> "$key=${value.contentToString()}"
				is LongArray    -> "$key=${value.contentToString()}"
				is FloatArray   -> "$key=${value.contentToString()}"
				is DoubleArray  -> "$key=${value.contentToString()}"
				is BooleanArray -> "$key=${value.contentToString()}"
				is CharArray    -> "$key=${value.contentToString()}"
				else            -> "$key=$value"
			}
		}
	})"
}

internal fun Intent.contentToString(): String {
	return "Intent(action=$action, data=$data, type=$type, component=$component, categories=$categories, flags=$flags, selector=${selector?.contentToString()}, extras=${extras?.contentToString()})"
}
