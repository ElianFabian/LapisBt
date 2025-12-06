package com.elianfabian.lapisbt.util

import kotlin.random.Random

internal val TestRandom = Random(1)


internal fun generateAddress(): String {
	return List(6) { TestRandom.nextInt(0, 255) }.joinToString(":") { byte -> "%02X".format(byte) }
}
