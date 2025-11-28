package com.elianfabian.lapisbt.app.common.data.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class BluetoothService : Service() {

	inner class BluetoothBinder : Binder() {
		fun getService() = this@BluetoothService
	}

	override fun onBind(intent: Intent): IBinder {
		return BluetoothBinder()
	}
}
