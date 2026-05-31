package com.elianfabian.lapisbt_rpc

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
