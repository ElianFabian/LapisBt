package com.elianfabian.lapisbt_rpc

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A default implementation of [LapisEncryption] using AES-GCM.
 *
 * This implementation prepends a 12-byte random IV to the ciphertext.
 *
 * @param key The 128, 192, or 256-bit AES key.
 */
internal class AesLapisEncryption(private val key: ByteArray) : LapisEncryption {

	override val sessionKey: ByteArray get() = key

	companion object {
		private const val ALGORITHM = "AES/GCM/NoPadding"
		private const val IV_LENGTH_BYTES = 12
		private const val TAG_LENGTH_BITS = 128
	}

	private val secretKeySpec = SecretKeySpec(key, "AES")
	private val secureRandom = SecureRandom()

	override fun encrypt(data: ByteArray, associatedData: ByteArray?): ByteArray {
		val iv = ByteArray(IV_LENGTH_BYTES)
		secureRandom.nextBytes(iv)

		val cipher = Cipher.getInstance(ALGORITHM)
		val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
		cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec)

		associatedData?.let {
			cipher.updateAAD(it)
		}

		val ciphertext = cipher.doFinal(data)

		val result = ByteArray(iv.size + ciphertext.size)
		System.arraycopy(iv, 0, result, 0, iv.size)
		System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

		return result
	}

	override fun decrypt(data: ByteArray, associatedData: ByteArray?): ByteArray {
		if (data.size < IV_LENGTH_BYTES) {
			throw IllegalArgumentException("Data too short to contain IV")
		}

		val iv = ByteArray(IV_LENGTH_BYTES)
		System.arraycopy(data, 0, iv, 0, IV_LENGTH_BYTES)

		val ciphertext = ByteArray(data.size - IV_LENGTH_BYTES)
		System.arraycopy(data, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

		val cipher = Cipher.getInstance(ALGORITHM)
		val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
		cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, parameterSpec)

		associatedData?.let {
			cipher.updateAAD(it)
		}

		return cipher.doFinal(ciphertext)
	}
}
