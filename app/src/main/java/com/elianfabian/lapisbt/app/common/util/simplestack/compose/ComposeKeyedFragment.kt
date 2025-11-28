package com.elianfabian.lapisbt.app.common.util.simplestack.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.elianfabian.lapisbt.app.common.util.asInstanceOf
import com.elianfabian.lapisbt.app.InnerPaddingProvider
import com.elianfabian.lapisbt.app.ui.theme.LapisBtTheme
import com.zhuinden.simplestackcomposeintegration.core.BackstackProvider
import com.zhuinden.simplestackextensions.fragments.KeyedFragment
import com.zhuinden.simplestackextensions.fragmentsktx.backstack

abstract class ComposeKeyedFragment : KeyedFragment() {

	@Composable
	abstract fun Content(innerPadding: PaddingValues)


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		requireActivity().window.decorView.rootView.post {
			onPostCreate()
		}
	}

	/**
	 * After process death it's not possible to get the backstack in onCreate().
	 *
	 * Issue's source: https://github.com/Zhuinden/simple-stack/issues/275
	 */
	protected open fun onPostCreate() {

	}

	final override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val composeView = ComposeView(requireContext())

		composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		composeView.setContent {
			val fragmentContainer = composeView.parent as InnerPaddingProvider

			ProvideAndroidComposeView {
				BackstackProvider(backstack) {
					LapisBtTheme {
						Content(fragmentContainer.innerPadding.value)
					}
				}
			}
		}

		return composeView
	}


	/**
	 * Set the LocalView.current view inside Fragments to be the AndroidComposeView used in Activities.
	 *
	 * This allows WindowInsets to work as expected.
	 *
	 * Source: https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeView.android.kt;l=226?q=androidcomposeview
	 */
	@Composable
	private fun ProvideAndroidComposeView(content: @Composable () -> Unit) {
		val androidComposeView = requireActivity().window.decorView
			.findViewById<ViewGroup>(android.R.id.content)
			.getChildAt(0)
			.asInstanceOf<ViewGroup>()
			.getChildAt(0) as? ViewGroup ?: throw IllegalStateException("Couldn't found AndroidComposeView")

		CompositionLocalProvider(LocalView provides androidComposeView, content)
	}
}
