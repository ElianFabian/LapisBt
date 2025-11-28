package com.elianfabian.lapisbt.app.common.util

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import java.lang.reflect.Method

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


private var cachedShouldShowRequestPermissionRationale: Method? = null

@SuppressLint("DiscouragedPrivateApi")
fun PackageManager.shouldShowRequestPermissionRationale(permission: String): Boolean {
	return try {
		val method = if (cachedShouldShowRequestPermissionRationale != null) {
			cachedShouldShowRequestPermissionRationale!!
		}
		else {
			cachedShouldShowRequestPermissionRationale = PackageManager::class.java.getDeclaredMethod(
				"shouldShowRequestPermissionRationale",
				String::class.java
			)
			cachedShouldShowRequestPermissionRationale!!
		}
		method.isAccessible = true
		method.invoke(this, permission) as Boolean
	}
	catch (e: Exception) {
		e.printStackTrace()
		false
	}
}
