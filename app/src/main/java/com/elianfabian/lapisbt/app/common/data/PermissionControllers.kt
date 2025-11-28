package com.elianfabian.lapisbt.app.common.data

import android.Manifest
import android.os.Build

class ReadContactsPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BasePermissionControllerImpl(mainActivityHolder) {
	override val permissionName: String
		get() = Manifest.permission.READ_CONTACTS
}

class PostNotificationsPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BasePermissionControllerImpl(mainActivityHolder) {
	override val permissionName: String
		get() = Manifest.permission.POST_NOTIFICATIONS
}


class BluetoothPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BaseMultiplePermissionControllerImpl(mainActivityHolder) {
	override val permissionNames: List<String>
		get() = buildList {
			if (Build.VERSION.SDK_INT >= 31) {
				add(Manifest.permission.BLUETOOTH_SCAN)
				add(Manifest.permission.BLUETOOTH_CONNECT)
				add(Manifest.permission.BLUETOOTH_ADVERTISE)
			}
//			else if (Build.VERSION.SDK_INT >= 23) {
//				add(Manifest.permission.ACCESS_FINE_LOCATION)
//			}
		}
}

class AccessFineLocationPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BasePermissionControllerImpl(mainActivityHolder) {
	override val permissionName: String
		get() = Manifest.permission.ACCESS_FINE_LOCATION
}
