package com.elianfabian.lapisbt.app.common.util.simplestack

import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.GlobalServices
import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestack.ServiceSearchMode
import com.zhuinden.simplestackextensions.servicesktx.add

inline fun <reified T> Backstack.forEachServiceOfType(
	searchMode: ServiceSearchMode = ServiceSearchMode.INCLUDE_PARENT_SERVICE,
	block: (service: T) -> Unit,
) {
	findServices(searchMode).forEach { result ->
		val service = result.service
		if (service is T) {
			block(service)
		}
	}
}

inline fun <reified T> GlobalServices.forEachServiceOfType(
	block: (service: T) -> Unit,
) {
	services().forEach { (_, service) ->
		if (service is T) {
			block(service)
		}
	}
}

fun ServiceBinder.onUnregistered(
	callback: () -> Unit,
) {
	add(object : ScopedServices.Registered {
		override fun onServiceRegistered() {
			// no-op
		}

		override fun onServiceUnregistered() {
			callback()
		}
	})
}
