package com.elianfabian.lapisbt_rpc.util

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Source: https://github.com/permissionlesstech/bitchat-android/blob/main/app/src/main/java/com/bitchat/android/protocol/CompressionUtil.kt
 */
internal object CompressionUtil {

	private const val COMPRESSION_THRESHOLD = 100


	fun shouldCompress(data: ByteArray): Boolean {

		// Don't compress if:
		// 1. Data is too small
		// 2. Data appears to be already compressed (high entropy)
		if (data.size < COMPRESSION_THRESHOLD) {
			return false
		}

		val present = BooleanArray(256)
		var uniqueCount = 0
		val maxPossibleUnique = minOf(data.size, 256)

		// If we have very high byte diversity, data is likely already compressed
		val thresholdLimit = maxPossibleUnique * 0.9

		// Simple entropy check - count unique bytes
		for (b in data) {
			val idx = b.toInt() and 0xFF
			if (!present[idx]) {
				present[idx] = true
				uniqueCount++

				if (uniqueCount >= thresholdLimit)
					return false
			}
		}

		return true
	}

	/**
	 * Compress data using deflate algorithm
	 */
	fun compress(data: ByteArray): ByteArray? {
		// Skip compression for small data
		if (data.size < COMPRESSION_THRESHOLD) return null

		try {
			// Use raw deflate format (no headers)
			val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // true = raw deflate, no headers
			deflater.setInput(data)
			deflater.finish()

			val outputStream = ByteArrayOutputStream(data.size)
			val buffer = ByteArray(1024)

			while (!deflater.finished()) {
				val count = deflater.deflate(buffer)
				outputStream.write(buffer, 0, count)
			}
			deflater.end()

			val compressedData = outputStream.toByteArray()

			// Only return if compression was beneficial
			return if (compressedData.size > 0 && compressedData.size < data.size) {
				compressedData
			}
			else {
				null
			}
		}
		catch (e: Exception) {
			return null
		}
	}

	/**
	 * Decompress deflate compressed data
	 */
	fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
		try {
			val inflater = Inflater(true) // true = raw deflate, no headers
			inflater.setInput(compressedData)

			val decompressedBuffer = ByteArray(originalSize)
			val actualSize = inflater.inflate(decompressedBuffer)
			inflater.end()

			// Verify decompressed size matches expected
			return if (actualSize == originalSize) {
				decompressedBuffer
			}
			else if (actualSize > 0) {
				// Handle case where actual size is different
				decompressedBuffer.copyOfRange(0, actualSize)
			}
			else {
				null
			}
		}
		catch (e: Exception) {
			Log.d("CompressionUtil", "Raw deflate decompression failed: ${e.message}, trying with zlib headers...")

			// Fallback: try with zlib headers in case of mixed usage
			try {
				val inflater = Inflater(false) // false = expect zlib headers
				inflater.setInput(compressedData)

				val decompressedBuffer = ByteArray(originalSize)
				val actualSize = inflater.inflate(decompressedBuffer)
				inflater.end()

				return if (actualSize == originalSize) {
					decompressedBuffer
				}
				else if (actualSize > 0) {
					decompressedBuffer.copyOfRange(0, actualSize)
				}
				else {
					null
				}
			}
			catch (fallbackException: Exception) {
				Log.e("CompressionUtil", "Both raw deflate and zlib decompression failed: ${fallbackException.message}")
				return null
			}
		}
	}

	/**
	 * Test function to verify deflate compression works correctly
	 * This can be called during app initialization to ensure compatibility
	 */
	fun testCompression(): Boolean {
		try {
			// Create test data that should compress well
			val testMessage = "This is a test message that should compress well. ".repeat(10)
			val originalData = testMessage.toByteArray()

			Log.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")

			// Test shouldCompress
			val shouldCompress = shouldCompress(originalData)
			Log.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")

			if (!shouldCompress) {
				Log.e("CompressionUtil", "shouldCompress failed for test data")
				return false
			}

			// Test compression
			val compressed = compress(originalData)
			if (compressed == null) {
				Log.e("CompressionUtil", "Compression failed")
				return false
			}

			Log.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")

			// Test decompression
			val decompressed = decompress(compressed, originalData.size)
			if (decompressed == null) {
				Log.e("CompressionUtil", "Decompression failed")
				return false
			}

			// Verify data integrity
			val isIdentical = originalData.contentEquals(decompressed)
			Log.d("CompressionUtil", "Data integrity check: $isIdentical")

			if (!isIdentical) {
				Log.e("CompressionUtil", "Decompressed data doesn't match original")
				return false
			}

			Log.i("CompressionUtil", "✅ deflate compression test PASSED - ready for iOS compatibility")
			return true

		}
		catch (e: Exception) {
			Log.e("CompressionUtil", "deflate compression test failed: ${e.message}")
			return false
		}
	}
}
