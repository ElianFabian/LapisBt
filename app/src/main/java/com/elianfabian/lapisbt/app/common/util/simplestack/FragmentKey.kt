package com.elianfabian.lapisbt.app.common.util.simplestack

import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider

/**
 * Base class for Fragment keys.
 */
abstract class FragmentKey(
	private val serviceModule: ServiceModule? = null,
) : CoroutineScopedFragmentKey(serviceModule),
	DefaultServiceProvider.HasServices {

	override fun getScopeTag(): String = toString()

	override fun bindServices(serviceBinder: ServiceBinder) {
		if (serviceModule is ServiceModule) {
			serviceModule.bindModuleServices(serviceBinder)
		}
	}
}

/**
 * Interface to allow to define the dependencies for a key outside of a Key class file.
 *
 * A instance of a module should not be shared among keys, a key must have it's own unique instance.
 */
interface ServiceModule {
	fun bindModuleServices(serviceBinder: ServiceBinder)
}
