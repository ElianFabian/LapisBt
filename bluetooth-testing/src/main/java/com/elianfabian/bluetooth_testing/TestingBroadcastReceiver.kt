package com.elianfabian.bluetooth_testing

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

class TestingBroadcastReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		Log.i(TAG, "START onReceive: ${intent.contentToString()}")

		val lapisBt = (context.applicationContext as Application).lapisBt

		val value: String = when (intent.action) {
			"get-state" -> {
				lapisBt.state.value.toString()
			}
			"get-isScanning" -> {
				lapisBt.isScanning.value.toString()
			}
			"get-activeBluetoothServersUuids" -> {
				lapisBt.activeBluetoothServersUuids.value.toString()
			}
			"get-scannedDevices" -> {
				lapisBt.scannedDevices.value.toJson()
			}
			"get-pairedDevices" -> {
				lapisBt.pairedDevices.value.toJson()
			}
			else -> throw UnsupportedOperationException("The action '${intent.action}' is not supported.")
		}

		setResult(Activity.RESULT_OK, value, Bundle.EMPTY)

		Log.i(TAG, "END onReceive: resultCode: $resultCode, resultData: $resultData")
	}


	companion object {
		const val TAG = "TestingBroadcastReceiver"
	}
}
