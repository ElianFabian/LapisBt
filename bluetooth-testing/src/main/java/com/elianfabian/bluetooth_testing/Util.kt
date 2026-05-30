package com.elianfabian.bluetooth_testing

import android.content.Intent
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlin.collections.contentToString
import kotlin.reflect.KClass
import kotlin.reflect.full.defaultType

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
			// Serializa directamente usando el método toString()
			override fun write(out: JsonWriter, value: T?) {
				if (value == null) {
					out.nullValue()
				} else {
					out.value(value.toString())
				}
			}

			// Deserializa parseando la cadena del toString()
			override fun read(reader: JsonReader): T? {
				val str = reader.nextString() ?: return null

				// 1. Intentar emparejar directamente con un 'data object'
				val objectInstance = kClass.sealedSubclasses
					.find { it.simpleName == str && it.objectInstance != null }
					?.objectInstance

				if (objectInstance != null) return objectInstance as T

				// 2. Si no es un object, es una data class (ej: "UnknownValue(value=42)")
				val subclass = kClass.sealedSubclasses.find {
					str.startsWith("${it.simpleName}(")
				} ?: throw JsonParseException("Unknown type string representation: $str")

				// Extraer el contenido dentro de los paréntesis: "value=42"
				val content = str.substringAfter("(").substringBeforeLast(")")

				val constructor = subclass.constructors.firstOrNull()
					?: throw JsonParseException("No constructor found for ${subclass.simpleName}")

				if (constructor.parameters.isEmpty()) {
					return constructor.call() as T
				}

				// Parsear los pares clave-valor del toString()
				val pairs = content.split(",").associate {
					val parts = it.split("=")
					parts[0].trim() to parts.getOrNull(1)?.trim()
				}

				// Mapear los tipos básicos de los parámetros del constructor
				val args = constructor.parameters.associateWith { param ->
					val paramValueStr = pairs[param.name]
					if (paramValueStr == null || paramValueStr == "null") {
						null
					} else {
						when (param.type.classifier as? KClass<*>) {
							Int::class -> paramValueStr.toIntOrNull()
							Long::class -> paramValueStr.toLongOrNull()
							Boolean::class -> paramValueStr.toBooleanStrictOrNull()
							String::class -> paramValueStr
							else -> throw JsonParseException("Unsupported parameter type [${param.type}] when parsing toString() for ${subclass.simpleName}")
						}
					}
				}

				return constructor.callBy(args) as T
			}
		} as TypeAdapter<T>
	}
}
