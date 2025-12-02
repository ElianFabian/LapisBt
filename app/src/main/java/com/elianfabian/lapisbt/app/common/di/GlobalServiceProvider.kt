package com.elianfabian.lapisbt.app.common.di

import android.app.Application
import android.content.Context
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.app.common.domain.NotificationController
import com.elianfabian.lapisbt.app.MainActivity
import com.elianfabian.lapisbt.app.common.data.AccessFineLocationPermissionController
import com.elianfabian.lapisbt.app.common.data.AndroidHelperImpl
import com.elianfabian.lapisbt.app.common.data.ApplicationOrchestrator
import com.elianfabian.lapisbt.app.common.data.BluetoothPermissionController
import com.elianfabian.lapisbt.app.common.data.MainActivityHolder
import com.elianfabian.lapisbt.app.common.data.NotificationControllerImpl
import com.elianfabian.lapisbt.app.common.data.PostNotificationsPermissionController
import com.elianfabian.lapisbt.app.common.data.ReadContactsPermissionController
import com.elianfabian.lapisbt.app.common.data.StorageControllerImpl
import com.elianfabian.lapisbt.app.common.domain.StorageController
import com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation.ManualBluetoothCommunicationViewModel
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

		val storageController: StorageController = StorageControllerImpl(
			context = applicationContext,
		)

		val mainActivityHolder = MainActivityHolder(mainActivity)

		val bluetoothPermissionController = BluetoothPermissionController(mainActivityHolder)
		val readContactsPermissionController = ReadContactsPermissionController(mainActivityHolder)

		val androidHelper: AndroidHelper = AndroidHelperImpl(
			context = applicationContext,
			applicationScope = applicationScope,
			mainActivityHolder = mainActivityHolder,
		)

		val lapisBt = LapisBt.newInstance(applicationContext)

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

		val manualBluetoothCommunicationViewModel = ManualBluetoothCommunicationViewModel(
			androidHelper = androidHelper,
			lapisBt = lapisBt,
			bluetoothPermissionController = bluetoothPermissionController,
			notificationController = notificationController,
			accessFineLocationPermissionController = accessFineLocationPermissionController,
			postNotificationsPermissionController = postNotificationsPermissionController,
			storageController = storageController,
		)

		val applicationOrchestrator = ApplicationOrchestrator(
			context = applicationContext,
			androidHelper = androidHelper,
			applicationScope = applicationScope,
			bluetoothPermissionController = bluetoothPermissionController,
			notificationController = notificationController,
		)

		val globalServices = GlobalServices.builder()
			.add(applicationContext, ApplicationContextTag)
			.add(applicationScope, ApplicationScopeTag)
			.add(storageController)
			.add(mainActivityHolder)
			.add(readContactsPermissionController)
			.add(bluetoothPermissionController)
			.add(lapisBt)
			.add(androidHelper)
			.add(manualBluetoothCommunicationViewModel)
			.add(notificationController)
			.add(applicationOrchestrator)
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
