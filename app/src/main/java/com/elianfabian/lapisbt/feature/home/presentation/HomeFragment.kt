package com.elianfabian.lapisbt.feature.home.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.ComposeKeyedFragment
import com.elianfabian.lapisbt.feature.home.presentation.component.HomeScreen

class HomeFragment : ComposeKeyedFragment() {

	@Composable
	override fun Content(innerPadding: PaddingValues) {

		HomeScreen(
			modifier = Modifier.padding(innerPadding)
		)
	}
}
