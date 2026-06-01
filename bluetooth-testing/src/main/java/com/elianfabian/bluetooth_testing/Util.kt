package com.elianfabian.bluetooth_testing

import android.content.Intent
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

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
	.registerTypeAdapterFactory(SealedClassToStringAdapterFactory())
	.create()

fun Any.toJson(): String = gson.toJson(this)

private class SealedClassToStringAdapterFactory : TypeAdapterFactory {
	@Suppress("UNCHECKED_CAST")
	override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
		val rawType = type.rawType
		val kClass = rawType.kotlin

		if (!kClass.isSealed) {
			return null
		}

		return object : TypeAdapter<T>() {
			override fun write(out: JsonWriter, value: T?) {
				if (value == null) {
					out.nullValue()
				}
				else {
					out.value(value.toString())
				}
			}

			override fun read(reader: JsonReader): T? {
				error("Deserialization of sealed classes is not supported")
			}
		} as TypeAdapter<T>
	}
}
