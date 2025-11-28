package com.elianfabian.lapisbt.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview

@Composable
fun LapisBtApp(
	content: @Composable (PaddingValues) -> Unit = {},
) {
	Scaffold(
		modifier = Modifier
			.fillMaxSize()
	) { innerPadding ->
		content(innerPadding)
	}
}


@Preview(showBackground = true)
@Composable
private fun Preview() = BasePreview {
	LapisBtApp()
}
