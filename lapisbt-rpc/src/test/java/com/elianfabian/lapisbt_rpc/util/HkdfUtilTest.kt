package com.elianfabian.lapisbt_rpc.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HkdfUtilTest {

	@Test
	fun `deriveKey produces expected length`() {
		val ikm = ByteArray(32) { 0x01.toByte() }
		val salt = ByteArray(32) { 0x02.toByte() }
		val info = "test".toByteArray()

		val key16 = HkdfUtil.deriveKey(ikm, salt, info, 16)
		val key32 = HkdfUtil.deriveKey(ikm, salt, info, 32)
		val key64 = HkdfUtil.deriveKey(ikm, salt, info, 64)

		assertThat(key16.size).isEqualTo(16)
		assertThat(key32.size).isEqualTo(32)
		assertThat(key64.size).isEqualTo(64)
	}

	@Test
	fun `deriveKey is deterministic`() {
		val ikm = "secret".toByteArray()
		val salt = "salt".toByteArray()
		val info = "info".toByteArray()

		val keyA = HkdfUtil.deriveKey(ikm, salt, info, 32)
		val keyB = HkdfUtil.deriveKey(ikm, salt, info, 32)

		assertThat(keyA).isEqualTo(keyB)
	}

	@Test
	fun `deriveKey changes with input`() {
		val ikm = "secret".toByteArray()
		val salt = "salt".toByteArray()

		val keyInfo1 = HkdfUtil.deriveKey(ikm, salt, "info1".toByteArray(), 32)
		val keyInfo2 = HkdfUtil.deriveKey(ikm, salt, "info2".toByteArray(), 32)

		assertThat(keyInfo1).isNotEqualTo(keyInfo2)
	}

	@Test
	fun `deriveKey handles null salt and info`() {
		val ikm = "rawMaterial".toByteArray()

		val key = HkdfUtil.deriveKey(ikm, targetLength = 32)

		assertThat(key.size).isEqualTo(32)

		assertThat(HkdfUtil.deriveKey(ikm, targetLength = 32)).isEqualTo(key)
	}

	@Test
	fun `extract and expand logic handles multiple iterations`() {
		val ikm = "multiIteration".toByteArray()
		val key = HkdfUtil.deriveKey(ikm, targetLength = 100)

		assertThat(key.size).isEqualTo(100)
	}
}
