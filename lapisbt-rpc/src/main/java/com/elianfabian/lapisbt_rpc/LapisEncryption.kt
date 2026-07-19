package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt_rpc.util.HkdfUtil
import com.elianfabian.lapisbt_rpc.util.LapisKeyExchange
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Interface for providing encryption and decryption capabilities to the RPC layer.
 *
 * Implementations should use a secure symmetric encryption algorithm (e.g., AES-GCM).
 */
public interface LapisEncryption {

	/**
	 * The symmetric key being used for this encryption session.
	 *
	 * This is optional and may be null if the implementation doesn't use a standard
	 * symmetric key or chooses not to expose it.
	 */
	public val sessionKey: ByteArray? get() = null


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
		 *
		 * @param pinnedPublicKeyHash The expected SHA-256 hash of the remote device's public key (X.509 encoded).
		 * If provided, the handshake will fail if the remote key's hash doesn't match.
		 * @param keyPair Optional local [KeyPair] to use for the handshake. If null, a random one is generated.
		 * @param hkdfInfo The info string used for HKDF key derivation.
		 *
		 * IMPORTANT: The `hkdfInfo` is used for domain separation. It ensures that the derived
		 * keys are unique to this specific application context or session. You can use a
		 * hardcoded string, or something dynamic (e.g., received via QR code or Cloud).
		 * Defaults to "LapisBT-RPC-v1".
		 */
		public fun automatic(
			pinnedPublicKeyHash: ByteArray? = null,
			keyPair: KeyPair? = null,
			hkdfInfo: String = "LapisBT-RPC-v1",
		): LapisEncryption {
			return AutomaticEncryptionMarker(
				pinnedPublicKeyHash = pinnedPublicKeyHash,
				keyPair = keyPair,
				hkdfInfo = hkdfInfo,
			)
		}

		/**
		 * Enables automatic encryption with custom key derivation.
		 *
		 * @param deriveKey A lambda that derives the session key from the raw shared secret.
		 * @param pinnedPublicKeyHash Optional public key hash for pinning.
		 * @param keyPair Optional local [KeyPair] for the handshake.
		 */
		public fun automatic(
			deriveKey: (sharedSecret: ByteArray) -> ByteArray,
			pinnedPublicKeyHash: ByteArray? = null,
			keyPair: KeyPair? = null,
		): LapisEncryption {
			return AutomaticEncryptionMarker(
				pinnedPublicKeyHash = pinnedPublicKeyHash,
				keyPair = keyPair,
				deriveKey = deriveKey,
			)
		}

		/**
		 * Enables automatic key exchange, but allows for a custom encryption implementation
		 * using the derived shared secret.
		 *
		 * @param pinnedPublicKeyHash The expected SHA-256 hash of the remote device's public key.
		 * @param keyPair Optional local [KeyPair] to use for the handshake.
		 * @param factory A factory that creates a [LapisEncryption] implementation from the shared secret.
		 */
		public fun automatic(
			pinnedPublicKeyHash: ByteArray? = null,
			keyPair: KeyPair? = null,
			factory: (sharedSecret: ByteArray) -> LapisEncryption,
		): LapisEncryption {
			return AutomaticEncryptionMarker(
				pinnedPublicKeyHash = pinnedPublicKeyHash,
				keyPair = keyPair,
				factory = factory,
			)
		}

		/**
		 * Enables asymmetric encryption using a fixed key pair.
		 *
		 * Note: This may have payload size limitations depending on the algorithm and MTU.
		 */
		public fun asymmetric(keyPair: KeyPair, remotePublicKey: PublicKey): LapisEncryption {
			return AsymmetricLapisEncryption(keyPair, remotePublicKey)
		}

		/**
		 * Generates a new EC [KeyPair] suitable for automatic encryption.
		 */
		public fun generateKeyPair(): KeyPair {
			return LapisKeyExchange.generateKeyPair()
		}

		/**
		 * Calculates the SHA-256 hash of the given [publicKey].
		 *
		 * This can be used to generate the hash for pinning.
		 */
		public fun calculatePublicKeyHash(publicKey: PublicKey): ByteArray {
			return calculatePublicKeyHash(publicKey.encoded)
		}

		/**
		 * Calculates the SHA-256 hash of the given [publicKeyBytes].
		 */
		public fun calculatePublicKeyHash(publicKeyBytes: ByteArray): ByteArray {
			return MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
		}

		/**
		 * Creates an encryption implementation from a manually derived shared secret.
		 *
		 * This applies the library's standard HKDF derivation to the [sharedSecret]
		 * using the provided [info] string.
		 *
		 * @param sharedSecret The raw shared secret derived from a key exchange.
		 * @param info The info string used for HKDF key derivation.
		 *
		 * IMPORTANT: The `info` string is used for domain separation. It ensures that the derived
		 * keys are unique to this specific application context or session. You can use a
		 * hardcoded string, or something dynamic (e.g., received via QR code or Cloud).
		 * Defaults to "LapisBT-RPC-v1".
		 */
		public fun fromSharedSecret(
			sharedSecret: ByteArray,
			info: String = "LapisBT-RPC-v1",
		): LapisEncryption {
			val sessionKey = HkdfUtil.deriveKey(
				inputKeyingMaterial = sharedSecret,
				info = info.toByteArray(),
				targetLength = 32,
			)
			return aesGcm(sessionKey)
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
	val pinnedPublicKeyHash: ByteArray? = null,
	val keyPair: KeyPair? = null,
	val hkdfInfo: String? = null,
	val deriveKey: ((sharedSecret: ByteArray) -> ByteArray)? = null,
	val factory: ((sharedSecret: ByteArray) -> LapisEncryption)? = null,
) : LapisEncryption {
	override fun encrypt(data: ByteArray, associatedData: ByteArray?): ByteArray = error("Marker only")
	override fun decrypt(data: ByteArray, associatedData: ByteArray?): ByteArray = error("Marker only")
}
