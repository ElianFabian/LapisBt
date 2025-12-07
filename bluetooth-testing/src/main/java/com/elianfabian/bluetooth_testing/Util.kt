package com.elianfabian.bluetooth_testing

import android.content.Intent
import android.os.Bundle
import com.google.gson.GsonBuilder
import kotlin.collections.contentToString

@Suppress("DEPRECATION")
fun Bundle.contentToString(): String {
	return "Bundle(${
		keySet().joinToString { key ->
			when (val value = get(key)) {
				is Bundle -> "$key=${value.contentToString()}"
				is Array<*> -> "$key=${value.contentToString()}"
				is IntArray -> "$key=${value.contentToString()}"
				is LongArray -> "$key=${value.contentToString()}"
				is FloatArray -> "$key=${value.contentToString()}"
				is DoubleArray -> "$key=${value.contentToString()}"
				is BooleanArray -> "$key=${value.contentToString()}"
				is CharArray -> "$key=${value.contentToString()}"
				else -> "$key=$value"
			}
		}
	})"
}

fun Intent?.contentToString(): String {
	if (this == null) {
		return "null"
	}
	return "Intent(action=$action, data=$data, type=$type, component=$component, categories=$categories, flags=$flags, selector=${selector?.contentToString()}, extras=${extras?.contentToString()})"
}

internal val gson = GsonBuilder()
	.serializeNulls()
	.create()

fun Any.toJson() = gson.toJson(this
)
