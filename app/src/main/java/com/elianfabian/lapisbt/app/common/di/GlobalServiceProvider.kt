package com.elianfabian.lapisbt.app.common.di

import android.app.Application
import android.content.Context
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp_prototype.common.domain.NotificationController
import com.elianfabian.lapisbt.app.MainActivity
import com.elianfabian.lapisbt.app.common.data.AccessFineLocationPermissionController
import com.elianfabian.lapisbt.app.common.data.AndroidHelperImpl
import com.elianfabian.lapisbt.app.common.data.ApplicationOrchestrator
import com.elianfabian.lapisbt.app.common.data.BluetoothPermissionController
import com.elianfabian.lapisbt.app.common.data.MainActivityHolder
import com.elianfabian.lapisbt.app.common.data.NotificationControllerImpl
import com.elianfabian.lapisbt.app.common.data.PostNotificationsPermissionController
import com.elianfabian.lapisbt.app.common.data.ReadContactsPermissionController
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.GlobalServices
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlobalServiceProvider(
	private val application: Application,
) {

	private var _globalServices: GlobalServices? = null


	fun create(backstack: Backstack, mainActivity: MainActivity): GlobalServices {

		if (_globalServices != null) {
			return _globalServices!!
		}

		val applicationContext: Context = application
		val applicationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

		val mainActivityHolder = MainActivityHolder(mainActivity)

		val bluetoothPermissionController = BluetoothPermissionController(mainActivityHolder)
		val readContactsPermissionController = ReadContactsPermissionController(mainActivityHolder)

		val androidHelper: AndroidHelper = AndroidHelperImpl(
			context = applicationContext,
			applicationScope = applicationScope,
			mainActivityHolder = mainActivityHolder,
		)

//		val bluetoothController: BluetoothController = BluetoothControllerImpl(
//			context = applicationContext,
//			applicationScope = applicationScope,
//			bluetoothPermissionController = bluetoothPermissionController,
//			androidHelper = androidHelper,
//		)

		val accessFineLocationPermissionController = AccessFineLocationPermissionController(mainActivityHolder)
		val postNotificationsPermissionController = PostNotificationsPermissionController(mainActivityHolder)

		val notificationController: NotificationController = NotificationControllerImpl(
			context = applicationContext,
			applicationScope = applicationScope,
		)

//		val viewModel = HomeViewModel(
//			bluetoothController = bluetoothController,
//			bluetoothPermissionController = bluetoothPermissionController,
//			accessFineLocationPermissionController = accessFineLocationPermissionController,
//			postNotificationsPermissionController = postNotificationsPermissionController,
//			notificationController = notificationController,
//			androidHelper = androidHelper,
//			applicationScope = applicationScope,
//		)

		val globalServices = GlobalServices.builder()
			.add(applicationContext, ApplicationContextTag)
			.add(applicationScope, ApplicationScopeTag)
			.add(mainActivityHolder)
			.add(readContactsPermissionController)
			.add(bluetoothPermissionController)
//			.add(bluetoothController)
			.add(androidHelper)
//			.add(viewModel)
			.add(notificationController)
			.add(
				ApplicationOrchestrator(
					context = applicationContext,
					androidHelper = androidHelper,
					applicationScope = applicationScope,
					bluetoothPermissionController = bluetoothPermissionController,
					notificationController = notificationController,
				)
			)
			.build()

		_globalServices = globalServices

		return globalServices
	}

	fun getOrNull(): GlobalServices? {
		return _globalServices
	}
}

private const val TagPrefix = "Tag.GlobalServices"

private const val ApplicationContextTag = "$TagPrefix.ApplicationContext"
private const val ApplicationScopeTag = "$TagPrefix.ApplicationScope"


fun ServiceBinder.lookupApplicationContext(): Context {
	return lookup(ApplicationContextTag)
}

fun ServiceBinder.lookupApplicationScope(): CoroutineScope {
	return lookup(ApplicationScopeTag)
}
