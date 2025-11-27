package com.elianfabian.lapisbt

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.elianfabian.lapisbt.ui.theme.LapisBtTheme

class MainActivity : ComponentActivity() {

	override fun onStart() {
		super.onStart()

		println("$$$ onStart")
	}

	override fun onStop() {
		super.onStop()

		println("$$$ onStop")
	}

	override fun onResume() {
		super.onResume()

		println("$$$ onResume")
	}

	override fun onPause() {
		super.onPause()

		println("$$$ onPause")
	}

	private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
		println("$$$ granted: $isGranted")
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			LapisBtTheme {
				Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
					Column {
						Greeting(
							name = "Android",
							modifier = Modifier.padding(innerPadding)
						)

						Button(
							onClick = {
								println("$$$ request permissions")
								permissionLauncher.launch(
									arrayOf(
										Manifest.permission.BLUETOOTH_CONNECT,
										Manifest.permission.BLUETOOTH_SCAN,
									)
								)
							}
						) {
							Text("Request permission")
						}
					}
				}
			}
		}
	}
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
	Text(
		text = "Hello $name!",
		modifier = modifier
	)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
	LapisBtTheme {
		Greeting("Android")
	}
}
