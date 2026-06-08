package com.elianfabian.lapisbt_rpc

import java.security.KeyPair
import java.security.PublicKey

/**
 * Interface for providing encryption and decryption capabilities to the RPC layer.
 *
 * Implementations should use a secure symmetric encryption algorithm (e.g., AES-GCM).
 */
public interface LapisEncryption {

	public companion object {
		/**
		 * Creates a new [LapisEncryption] implementation using AES-GCM.
		 *
		 * @param key The 128, 192, or 256-bit AES key.
		 */
		public fun aesGcm(key: ByteArray): LapisEncryption {
			return AesLapisEncryption(key)
		}

		/**
		 * Enables automatic encryption with ECDH key exchange and default AES-GCM implementation.
		 */
		public fun automatic(): LapisEncryption {
			return AutomaticEncryptionMarker()
		}

		/**
		 * Enables automatic key exchange, but allows for a custom encryption implementation
		 * using the derived shared secret.
		 */
		public fun automatic(factory: (sharedSecret: ByteArray) -> LapisEncryption): LapisEncryption {
			return AutomaticEncryptionMarker(factory)
		}

		/**
		 * Enables asymmetric encryption using a fixed key pair.
		 *
		 * Note: This may have payload size limitations depending on the algorithm and MTU.
		 */
		public fun asymmetric(keyPair: KeyPair, remotePublicKey: PublicKey): LapisEncryption {
			return AsymmetricLapisEncryption(keyPair, remotePublicKey)
		}
	}

	/**
	 * Encrypts the given [data].
	 *
	 * @param data The plaintext data to encrypt.
	 * @param associatedData Optional associated data for AEAD (Authenticated Encryption with Associated Data).
	 * @return The encrypted ciphertext.
	 */
	public fun encrypt(data: ByteArray, associatedData: ByteArray? = null): ByteArray

	/**
	 * Decrypts the given [data].
	 *
	 * @param data The ciphertext data to decrypt.
	 * @param associatedData Optional associated data for AEAD.
	 * @return The decrypted plaintext.
	 */
	public fun decrypt(data: ByteArray, associatedData: ByteArray? = null): ByteArray
}

internal class AutomaticEncryptionMarker(
	val factory: ((sharedSecret: ByteArray) -> LapisEncryption)? = null
) : LapisEncryption {
	override fun encrypt(data: ByteArray, associatedData: ByteArray?): ByteArray = error("Marker only")
	override fun decrypt(data: ByteArray, associatedData: ByteArray?): ByteArray = error("Marker only")
}
