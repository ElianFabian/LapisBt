package com.elianfabian.lapisbt_rpc.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * A lightweight implementation of HKDF (RFC 5869) using HMAC-SHA256.
 */
internal object HkdfUtil {

	private const val ALGORITHM = "HmacSHA256"
	private const val HASH_LEN = 32 // SHA-256 length in bytes

	/**
	 * Extracts and expands the [inputKeyingMaterial] into a derived key of [targetLength].
	 */
	fun deriveKey(
		inputKeyingMaterial: ByteArray,
		salt: ByteArray? = null,
		info: ByteArray? = null,
		targetLength: Int,
	): ByteArray {
		val prk = extract(salt ?: ByteArray(HASH_LEN), inputKeyingMaterial)
		return expand(prk, info, targetLength)
	}

	private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
		val mac = Mac.getInstance(ALGORITHM)
		mac.init(SecretKeySpec(salt, ALGORITHM))
		return mac.doFinal(ikm)
	}

	private fun expand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
		val iterations = ceil(length.toDouble() / HASH_LEN).toInt()
		if (iterations > 255) throw IllegalArgumentException("Derived key too long")

		val mac = Mac.getInstance(ALGORITHM)
		mac.init(SecretKeySpec(prk, ALGORITHM))

		val result = ByteArray(length)
		var t = ByteArray(0)
		var remaining = length

		for (i in 1..iterations) {
			mac.update(t)
			info?.let { mac.update(it) }
			mac.update(i.toByte())
			t = mac.doFinal()

			val stepLen = minOf(remaining, HASH_LEN)
			System.arraycopy(t, 0, result, (i - 1) * HASH_LEN, stepLen)
			remaining -= stepLen
		}

		return result
	}
}
