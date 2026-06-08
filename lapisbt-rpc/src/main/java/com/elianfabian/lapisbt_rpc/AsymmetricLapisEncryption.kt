package com.elianfabian.lapisbt_rpc

import java.security.KeyPair
import java.security.PublicKey
import javax.crypto.Cipher

/**
 * An implementation of [LapisEncryption] using asymmetric encryption (RSA).
 *
 * NOTE: This has significant payload size limitations due to the nature of asymmetric encryption
 * and the typical Bluetooth MTU. For a 2048-bit RSA key, the maximum plaintext size is
 * approximately 190 bytes with OAEP padding.
 */
internal class AsymmetricLapisEncryption(
	private val keyPair: KeyPair,
	private val remotePublicKey: PublicKey
) : LapisEncryption {

	companion object {
		private const val ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
	}

	override fun encrypt(data: ByteArray, associatedData: ByteArray?): ByteArray {
		// Associated data is not supported for standard RSA
		val cipher = Cipher.getInstance(ALGORITHM)
		cipher.init(Cipher.ENCRYPT_MODE, remotePublicKey)
		return cipher.doFinal(data)
	}

	override fun decrypt(data: ByteArray, associatedData: ByteArray?): ByteArray {
		val cipher = Cipher.getInstance(ALGORITHM)
		cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
		return cipher.doFinal(data)
	}
}
