package com.elianfabian.lapisbt.app.common.util.simplestack.compose

import androidx.compose.runtime.Composable
import com.elianfabian.lapisbt.app.ui.theme.LapisBtTheme
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestackcomposeintegration.core.BackstackProvider

@Composable
fun BasePreview(
	content: @Composable () -> Unit,
) {
	BackstackProvider(backstack = Backstack()) {
		LapisBtTheme {
			content()
		}
	}
}
