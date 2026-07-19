package com.elianfabian.lapisbt_rpc

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class AesLapisEncryptionTest {

	private val key = ByteArray(32) { it.toByte() }
	private val encryption = LapisEncryption.aesGcm(key)

	@Test
	fun `encryption and decryption works with same AAD`() {
		val plaintext = "Hello World".toByteArray()
		val aad = "PacketMetadata".toByteArray()

		val ciphertext = encryption.encrypt(plaintext, aad)
		val decrypted = encryption.decrypt(ciphertext, aad)

		assertThat(decrypted).isEqualTo(plaintext)
	}

	@Test
	fun `decryption fails when AAD is tampered`() {
		val plaintext = "Secure Data".toByteArray()
		val aad = "Original AAD".toByteArray()
		val tamperedAad = "Tampered AAD".toByteArray()

		val ciphertext = encryption.encrypt(plaintext, aad)

		assertThrows(GeneralSecurityException::class.java) {
			encryption.decrypt(ciphertext, tamperedAad)
		}
	}

	@Test
	fun `decryption fails when even one bit of AAD changes`() {
		val plaintext = "Confidential".toByteArray()
		val aad = byteArrayOf(0x01, 0x02, 0x03)
		val tamperedAad = byteArrayOf(0x01, 0x02, 0x04) // Changed last bit

		val ciphertext = encryption.encrypt(plaintext, aad)

		assertThrows(GeneralSecurityException::class.java) {
			encryption.decrypt(ciphertext, tamperedAad)
		}
	}

	@Test
	fun `different IVs are used for same plaintext`() {
		val plaintext = "Repeated Message".toByteArray()

		val ciphertext1 = encryption.encrypt(plaintext)
		val ciphertext2 = encryption.encrypt(plaintext)

		assertThat(ciphertext1).isNotEqualTo(ciphertext2)
	}

	@Test
	fun `decryption fails when ciphertext is tampered`() {
		val plaintext = "Untamperable".toByteArray()
		val ciphertext = encryption.encrypt(plaintext)

		ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 1).toByte()

		assertThrows(GeneralSecurityException::class.java) {
			encryption.decrypt(ciphertext)
		}
	}
}
