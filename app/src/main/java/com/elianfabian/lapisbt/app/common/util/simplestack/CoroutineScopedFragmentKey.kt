package com.elianfabian.lapisbt.app.common.util.simplestack

import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.simplestackextensions.fragments.DefaultFragmentKey
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/**
 * Base class for Fragment keys that provides coroutine scopes for services to use.
 *
 * [ServiceProvider] treats [CoroutineScopedFragmentKey] as a service, this allows us to implement
 * [registeredScope] and [activatedScope].
 *
 * This is an experimental class, it may be changed or removed in future in case
 * we observe that is less reliable than implementing coroutine scopes inside services.
 */
abstract class CoroutineScopedFragmentKey(
	private val serviceModule: ServiceModule?,
) : DefaultFragmentKey(),
	DefaultServiceProvider.HasServices,
	ScopedServices.Registered,
	ScopedServices.Activated {

	init {
		@Suppress("LeakingThis")
		if (serviceModule is ServiceKeyOwner) {
			serviceModule.key = this
		}
	}


	/**
	 * Scope bound to the lifecycle of the [ScopedServices.Registered] callbacks.
	 */
	val registeredScope: CoroutineScope = ReactivableCoroutineScope("RegisteredScope")

	/**
	 * Scope bound to the lifecycle of the [ScopedServices.Activated] callbacks.
	 */
	val activatedScope: CoroutineScope = ReactivableCoroutineScope("ActivatedScope")


	@Deprecated(level = DeprecationLevel.HIDDEN, message = "")
	final override fun onServiceRegistered() {
		// Since keys can be objects (singletons) we must reactive the registeredScope ourselves
		(registeredScope as ReactivableCoroutineScope).reactivate()
	}

	@Deprecated(level = DeprecationLevel.HIDDEN, message = "")
	final override fun onServiceUnregistered() {
		activatedScope.cancel()
		registeredScope.cancel()

		if (serviceModule is ServiceKeyOwner) {
			serviceModule.key = null
		}
	}

	@Deprecated(level = DeprecationLevel.HIDDEN, message = "")
	final override fun onServiceActive() {
		(activatedScope as ReactivableCoroutineScope).reactivate()
	}

	@Deprecated(level = DeprecationLevel.HIDDEN, message = "")
	final override fun onServiceInactive() {
		activatedScope.cancel()
	}


	@Suppress("SpellCheckingInspection")
	private class ReactivableCoroutineScope(
		name: String,
	) : CoroutineScope {

		override var coroutineContext: CoroutineContext = Dispatchers.Main.immediate + SupervisorJob() + CoroutineName(name)

		fun reactivate() {
			if (!isActive) {
				coroutineContext += SupervisorJob()
			}
		}
	}
}


/**
 * Default ServiceModule implementation that provides registeredScope and activatedScope.
 *
 * If you are using Service modules you should not use [FragmentKey.bindServices].
 */
abstract class CoroutineScopedServiceModule : ServiceModule, ServiceKeyOwner {

	final override var key: CoroutineScopedFragmentKey? = null
		set(newKey) {
			if (field != null && newKey != null) {
				throw IllegalStateException("Can't use service module '$this' for key '$newKey' because is already being used by the key '$field'")
			}
			field = newKey
		}

	protected val registeredScope: CoroutineScope
		get() = key?.registeredScope
			?: throw IllegalStateException("Can't access registeredScope because the FragmentKey in module '$this' is null")
	protected val activatedScope: CoroutineScope
		get() = key?.activatedScope
			?: throw IllegalStateException("Can't access activatedScope because the FragmentKey in module '$this' is null")
}

/**
 * Internal interface to implement registeredScope and activatedScope in ServiceModule.
 */
private interface ServiceKeyOwner {
	var key: CoroutineScopedFragmentKey?
}
