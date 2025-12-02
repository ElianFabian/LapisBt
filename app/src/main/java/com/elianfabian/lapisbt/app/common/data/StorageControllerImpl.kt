package com.elianfabian.lapisbt.app.common.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elianfabian.lapisbt.app.common.domain.StorageController

class StorageControllerImpl(
	private val context: Context,
) : StorageController {

	private val _preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)


	override fun getBluetoothAddress(): String? {
		return _preferences.getString(BluetoothAddress, "")?.ifEmpty {
			null
		}
	}

	override fun setBluetoothAddress(value: String?) {
		_preferences.edit {
			putString(BluetoothAddress, value)
		}
	}


	companion object {
		private const val BluetoothAddress = "bluetooth_address"
	}
}
