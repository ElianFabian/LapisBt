package com.elianfabian.lapisbt.util

import android.content.Intent
import android.os.Bundle
import kotlin.collections.contentToString

@Suppress("DEPRECATION")
internal fun Bundle.contentToString(): String {
	return "Bundle(${
		keySet().joinToString { key ->
			when (val value = get(key)) {
				is Bundle       -> "$key=${value.contentToString()}"
				is Array<*>     -> "$key=${value.contentToString()}"
				is IntArray     -> "$key=${value.contentToString()}"
				is LongArray    -> "$key=${value.contentToString()}"
				is FloatArray   -> "$key=${value.contentToString()}"
				is DoubleArray  -> "$key=${value.contentToString()}"
				is BooleanArray -> "$key=${value.contentToString()}"
				is CharArray    -> "$key=${value.contentToString()}"
				else            -> "$key=$value"
			}
		}
	})"
}

internal fun Intent.contentToString(): String {
	return "Intent(action=$action, data=$data, type=$type, component=$component, categories=$categories, flags=$flags, selector=${selector?.contentToString()}, extras=${extras?.contentToString()})"
}
