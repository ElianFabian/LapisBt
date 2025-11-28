package com.elianfabian.lapisbt.app.common.util.simplestack

import android.os.Handler
import android.os.Looper
import androidx.annotation.AnimRes
import androidx.annotation.AnimatorRes
import androidx.annotation.IdRes
import androidx.annotation.TransitionRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.zhuinden.simplestack.StateChange
import com.zhuinden.simplestackextensions.fragments.DefaultFragmentKey

/**
 * Refer to the documentation of [com.zhuinden.simplestackextensions.fragments.DefaultFragmentStateChanger].
 */
class FragmentStateChanger(
	private val fragmentManager: FragmentManager,
	@param:IdRes
	private val containerId: Int,
) {
	private val handler = Handler(Looper.getMainLooper())

	private fun isNotShowing(fragment: Fragment): Boolean {
		return fragment.isDetached
	}

	private fun startShowing(fragmentTransaction: FragmentTransaction, fragment: Fragment) {
		fragmentTransaction.attach(fragment) // show top fragment if already exists
	}

	private fun stopShowing(fragmentTransaction: FragmentTransaction, fragment: Fragment) {
		fragmentTransaction.detach(fragment) // destroy view of fragment not top
	}

	fun handleStateChange(stateChange: StateChange) {
		var didExecutePendingTransactions = false

		try {
			fragmentManager.executePendingTransactions() // two synchronous immediate fragment transactions can overlap.
			didExecutePendingTransactions = true
		}
		catch (_: IllegalStateException) { // executePendingTransactions() can fail, but this should "just work".
		}

		if (didExecutePendingTransactions) {
			executeFragmentTransaction(stateChange)
		}
		else { // failed to execute pending transactions
			if (!fragmentManager.isDestroyed) { // ignore state change if activity is dead. :(
				handler.post {
					if (!fragmentManager.isDestroyed) { // ignore state change if activity is dead. :(
						executeFragmentTransaction(stateChange)
					}
				}
			}
		}
	}

	private fun executeFragmentTransaction(stateChange: StateChange) {
		val fragmentTransaction = fragmentManager.beginTransaction().disallowAddToBackStack()
		val topNewKey = stateChange.topNewKey<DefaultFragmentKey>()
		val topPreviousKey = stateChange.topPreviousKey<DefaultFragmentKey>()

		val key = if (stateChange.direction == StateChange.BACKWARD) {
			topPreviousKey
		}
		else topNewKey

		val animationWasApplied = if (key is NavigationInterceptor) {
			key.onNavigate(
				animationHandler = FragmentAnimationHandlerImpl(fragmentTransaction = fragmentTransaction),
				stateChange = stateChange,
			)
		}
		else false

		if (!animationWasApplied) {
			when (stateChange.direction) {
				StateChange.FORWARD -> {
					fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_NONE)
				}
				StateChange.BACKWARD -> {
					fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_NONE)
				}
				else -> {
					fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
				}
			}
		}

		var topNewFragment = fragmentManager.findFragmentByTag(topNewKey.fragmentTag)

		if (topNewFragment == null || topNewFragment.isRemoving) {
			topNewFragment = topNewKey.createFragment() // create new fragment here, ahead of time
		}

		val previousKeys: List<DefaultFragmentKey> = stateChange.getPreviousKeys()
		val newKeys: List<DefaultFragmentKey> = stateChange.getNewKeys()
		for (oldKey in previousKeys) {
			val fragment = fragmentManager.findFragmentByTag(oldKey.fragmentTag)
			if (fragment != null) {
				if (!newKeys.contains(oldKey)) {
					fragmentTransaction.remove(fragment) // remove fragments not in backstack
				}
				else if (!isNotShowing(fragment)) {
					stopShowing(fragmentTransaction, fragment)
				}
			}
		}
		for (newKey in newKeys) {
			val fragment = fragmentManager.findFragmentByTag(newKey.fragmentTag)
			if (newKey == stateChange.topNewKey<Any>()) {
				if (fragment != null) {
					if (fragment.isRemoving) { // fragments are quirky, they die asynchronously. Ignore if they're still there.
						fragmentTransaction.replace(containerId, topNewFragment, newKey.fragmentTag)
					}
					else if (isNotShowing(fragment)) {
						startShowing(fragmentTransaction, fragment)
					}
				}
				else {
					// add the newly created fragment if it's not there yet
					fragmentTransaction.add(containerId, topNewFragment, newKey.fragmentTag)
				}
			}
			else {
				if (fragment != null && !isNotShowing(fragment)) {
					stopShowing(fragmentTransaction, fragment)
				}
			}
		}
		fragmentTransaction.commitAllowingStateLoss()
	}


	interface FragmentAnimationHandler {

		fun setTransition(@TransitionRes resId: Int)

		fun setCustomAnimations(
			@AnimatorRes @AnimRes
			enter: Int,
			@AnimatorRes @AnimRes
			exit: Int,
			@AnimatorRes @AnimRes
			popEnter: Int = enter,
			@AnimatorRes @AnimRes
			popExit: Int = exit,
		)
	}

	private class FragmentAnimationHandlerImpl(
		private val fragmentTransaction: FragmentTransaction,
	) : FragmentAnimationHandler {

		override fun setTransition(resId: Int) {
			fragmentTransaction.setTransition(resId)
		}

		override fun setCustomAnimations(
			enter: Int,
			exit: Int,
			popEnter: Int,
			popExit: Int,
		) {
			fragmentTransaction.setCustomAnimations(enter, exit, popEnter, popExit)
		}
	}


	// I will see if this might be helpful to implement custom navigation animations
	interface NavigationInterceptor {
		/**
		 * Returns true if a custom animation has been applied to avoid using the defaults.
		 */
		fun onNavigate(animationHandler: FragmentAnimationHandler, stateChange: StateChange): Boolean
	}

	fun setNavigationInterceptor(interceptor: NavigationInterceptor?) {
		_interceptor = interceptor
	}

	private var _interceptor: NavigationInterceptor? = null
}
