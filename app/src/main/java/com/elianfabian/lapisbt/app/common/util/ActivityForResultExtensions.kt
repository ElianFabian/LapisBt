package com.elianfabian.lapisbt.app.common.util

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.Fragment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private fun <I, O, A, B> registerForActivityResultCallbackInternal(
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> B,
	contract: ActivityResultContract<I, O>,
	registerForActivityResult: (
		contract: ActivityResultContract<I, O>,
		callback: ActivityResultCallback<O>,
	) -> ActivityResultLauncher<I>,
): (input: A, callback: (result: B) -> Unit) -> Unit {

	var callbackRef: ((B) -> Unit)? = null

	val resultLauncher = registerForActivityResult(contract) { result ->
		callbackRef?.invoke(transformOutput(result))
		callbackRef = null
	}

	return { input: A, callback: (result: B) -> Unit ->
		callbackRef = callback
		resultLauncher.launch(transformInput(input))
	}
}

private fun <I, O, A, B> registerForActivityResultSuspendInternal(
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> B,
	contract: ActivityResultContract<I, O>,
	registerForActivityResult: (
		contract: ActivityResultContract<I, O>,
		callback: ActivityResultCallback<O>,
	) -> ActivityResultLauncher<I>,
): suspend (input: A) -> B {
	val channel = Channel<O>()

	val resultLauncher = registerForActivityResult(contract) { result ->
		channel.trySend(result)
	}

	return { input: A ->
		resultLauncher.launch(transformInput(input))

		transformOutput(channel.receive())
	}
}


fun <I, O> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
): (input: I, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallback(
		contract = contract,
		transformInput = { it },
		transformOutput = { it },
	)
}

fun <I, O> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
): (callback: (result: O) -> Unit) -> Unit {
	val function = registerForActivityResultCallback(
		contract = contract,
		transformOutput = { it },
	)

	return { callback ->
		function(input, callback)
	}
}


@JvmName("registerForActivityResultTransformInputCallback")
fun <I, O, A> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
): (input: A, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = transformInput,
		transformOutput = { it },
	)
}

@JvmName("registerForActivityResultTransformOutputCallback")
fun <I, O, B> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> B,
): (input: I, callback: (result: B) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = { it },
		transformOutput = transformOutput,
	)
}

fun <I, O, B> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> B,
): (callback: (result: B) -> Unit) -> Unit {
	val function = registerForActivityResultCallback(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { callback ->
		function(input, callback)
	}
}

fun <I, O, A> ComponentActivity.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> O = { it },
): (input: A, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = transformInput,
		transformOutput = transformOutput,
	)
}


fun <I, O> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
): suspend (input: I) -> O {
	return this.registerForActivityResultSuspend(
		contract = contract,
		transformInput = { it },
		transformOutput = { it },
	)
}

fun <I, O> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	input: I,
): suspend () -> O {
	val function = registerForActivityResultSuspend(
		contract = contract,
		transformOutput = { it },
	)
	return { function(input) }
}

@JvmName("registerForActivityResultTransformInputSuspend")
fun <I, O, A> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
): suspend (input: A) -> O {
	return registerForActivityResultSuspendInternal(
		contract = contract,
		transformInput = transformInput,
		transformOutput = { it },
		registerForActivityResult = ::registerForActivityResult,
	)
}

@JvmName("registerForActivityResultTransformOutputSuspend")
fun <I, O, B> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> B,
): suspend (input: I) -> B {
	return this.registerForActivityResultSuspend(
		contract = contract,
		transformInput = { it },
		transformOutput = transformOutput,
	)
}

fun <I, O, B> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> B,
): suspend (I) -> B {
	val function = registerForActivityResultSuspend(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { function(input) }
}

fun <I, O, A, B> ComponentActivity.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> B,
): suspend (input: A) -> B {
	return registerForActivityResultSuspendInternal(
		contract = contract,
		transformInput = transformInput,
		transformOutput = transformOutput,
		registerForActivityResult = ::registerForActivityResult,
	)
}


fun <I, O> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
): (input: I, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallback(
		contract = contract,
		transformInput = { it },
		transformOutput = { it },
	)
}

fun <I, O> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
): (callback: (result: O) -> Unit) -> Unit {
	val function = registerForActivityResultCallback(
		contract = contract,
		transformOutput = { it },
	)

	return { callback ->
		function(input, callback)
	}
}

@JvmName("registerForActivityResultTransformInputCallback")
fun <I, O, A> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
): (input: A, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = transformInput,
		transformOutput = { it },
	)
}

@JvmName("registerForActivityResultTransformOutputCallback")
fun <I, O, B> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> B,
): (input: I, callback: (result: B) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = { it },
		transformOutput = transformOutput,
	)
}

fun <I, O, B> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> B,
): (callback: (result: B) -> Unit) -> Unit {
	val function = registerForActivityResultCallback(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { callback ->
		function(input, callback)
	}
}

fun <I, O, A> Fragment.registerForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> O = { it },
): (input: A, callback: (result: O) -> Unit) -> Unit {
	return registerForActivityResultCallbackInternal(
		contract = contract,
		registerForActivityResult = ::registerForActivityResult,
		transformInput = transformInput,
		transformOutput = transformOutput,
	)
}


fun <I, O> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
): suspend (input: I) -> O {
	return this.registerForActivityResultSuspend(
		contract = contract,
		transformInput = { it },
		transformOutput = { it },
	)
}

fun <I, O> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	input: I,
): suspend () -> O {
	val function = registerForActivityResultSuspend(
		contract = contract,
		transformOutput = { it },
	)
	return { function(input) }
}

@JvmName("registerForActivityResultTransformInputSuspend")
fun <I, O, A> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
): suspend (input: A) -> O {
	return registerForActivityResultSuspendInternal(
		contract = contract,
		transformInput = transformInput,
		transformOutput = { it },
		registerForActivityResult = ::registerForActivityResult,
	)
}

@JvmName("registerForActivityResultTransformOutputSuspend")
fun <I, O, B> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> B,
): suspend (input: I) -> B {
	return this.registerForActivityResultSuspend(
		contract = contract,
		transformInput = { it },
		transformOutput = transformOutput,
	)
}

fun <I, O, B> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> B,
): suspend (I) -> B {
	val function = registerForActivityResultSuspend(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { function(input) }
}

fun <I, O, A, B> Fragment.registerForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformInput: (input: A) -> I,
	transformOutput: (output: O) -> B,
): suspend (input: A) -> B {
	return registerForActivityResultSuspendInternal(
		contract = contract,
		transformInput = transformInput,
		transformOutput = transformOutput,
		registerForActivityResult = ::registerForActivityResult,
	)
}

// ---------- Compose ----------

@Composable
fun <I, O, A> rememberLauncherForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transform: (input: A) -> I,
	transformOutput: (output: O) -> O = { it },
): suspend (
	input: A,
) -> O {
	val channel = remember { Channel<O>() }


	val resultLauncher = rememberLauncherForActivityResult(contract) { result ->
		channel.trySend(result)
	}

	return { input: A ->
		resultLauncher.launch(transform(input))

		transformOutput(channel.receive())
	}
}

@Composable
fun <I, O, A, B> rememberLauncherForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transform: (input: A) -> I,
	transformOutput: (output: O) -> B,
): (input: A, callback: (result: B) -> Unit) -> Unit {

	val channel = remember { Channel<O>() }

	val scope = rememberCoroutineScope()

	val resultLauncher = rememberLauncherForActivityResult(contract) { result ->
		channel.trySend(result)
	}

	return { input: A, callback: (result: B) -> Unit ->
		resultLauncher.launch(transform(input))

		scope.launch {
			val result = channel.receive()
			callback(transformOutput(result))
		}
		Unit
	}
}


@Composable
fun <I, O> rememberLauncherForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> O = { it },
): suspend (input: I) -> O {
	return rememberLauncherForActivityResultSuspend(
		contract = contract,
		transform = { it },
		transformOutput = transformOutput,
	)
}

@Composable
fun <I, O> rememberLauncherForActivityResultSuspend(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> O = { it },
): suspend () -> O {
	val function = rememberLauncherForActivityResultSuspend(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { function(input) }
}

@Composable
fun <I, O, B> rememberLauncherForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	transformOutput: (output: O) -> B,
): (input: I, callback: (result: B) -> Unit) -> Unit {
	return rememberLauncherForActivityResultCallback(
		contract = contract,
		transform = { it },
		transformOutput = transformOutput,
	)
}

@Composable
fun <I, O> rememberLauncherForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
): (input: I, callback: (result: O) -> Unit) -> Unit {
	return rememberLauncherForActivityResultCallback(
		contract = contract,
		transform = { it },
		transformOutput = { it },
	)
}

@Composable
fun <I, O, B> rememberLauncherForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
	transformOutput: (output: O) -> B,
): (callback: (result: B) -> Unit) -> Unit {
	val function = rememberLauncherForActivityResultCallback(
		contract = contract,
		transformOutput = transformOutput,
	)

	return { callback ->
		function(input, callback)
	}
}

@Composable
fun <I, O> rememberLauncherForActivityResultCallback(
	contract: ActivityResultContract<I, O>,
	input: I,
): (callback: (result: O) -> Unit) -> Unit {
	val function = rememberLauncherForActivityResultCallback(
		contract = contract,
		transformOutput = { it },
	)

	return { callback ->
		function(input, callback)
	}
}
