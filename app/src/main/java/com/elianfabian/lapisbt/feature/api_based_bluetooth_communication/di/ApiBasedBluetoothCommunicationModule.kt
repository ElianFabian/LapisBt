package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.di

import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.app.common.data.AccessFineLocationPermissionController
import com.elianfabian.lapisbt.app.common.data.BluetoothPermissionController
import com.elianfabian.lapisbt.app.common.data.PostNotificationsPermissionController
import com.elianfabian.lapisbt.app.common.di.lookupApplicationContext
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import com.elianfabian.lapisbt.app.common.domain.NotificationController
import com.elianfabian.lapisbt.app.common.domain.StorageController
import com.elianfabian.lapisbt.app.common.util.simplestack.ServiceModule
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation.ApiBasedBluetoothCommunicationViewModel
import com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation.ManualBluetoothCommunicationViewModel
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup

object ApiBasedBluetoothCommunicationModule : ServiceModule {

	override fun bindModuleServices(serviceBinder: ServiceBinder) {
		val applicationContext = serviceBinder.lookupApplicationContext()

		val lapisBt = LapisBt.newInstance(applicationContext)
		val androidHelper = serviceBinder.lookup<AndroidHelper>()
		val bluetoothPermissionController = serviceBinder.lookup<BluetoothPermissionController>()
		val accessFineLocationPermissionController = serviceBinder.lookup<AccessFineLocationPermissionController>()
		val postNotificationsPermissionController = serviceBinder.lookup<PostNotificationsPermissionController>()
		val storageController = serviceBinder.lookup<StorageController>()
		val lapisBtRpc = LapisBtRpc.newInstance(lapisBt)

		val manualBluetoothCommunicationViewModel = ApiBasedBluetoothCommunicationViewModel(
			androidHelper = androidHelper,
			lapisBt = lapisBt,
			lapisBtRpc = lapisBtRpc,
			bluetoothPermissionController = bluetoothPermissionController,
			accessFineLocationPermissionController = accessFineLocationPermissionController,
			postNotificationsPermissionController = postNotificationsPermissionController,
			storageController = storageController,
		)

		serviceBinder.add(manualBluetoothCommunicationViewModel)
	}
}
