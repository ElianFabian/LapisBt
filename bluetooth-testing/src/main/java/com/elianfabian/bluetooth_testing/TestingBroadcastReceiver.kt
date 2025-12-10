package com.elianfabian.bluetooth_testing

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import kotlin.math.min

class TestingBroadcastReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		Log.i(TAG, "START onReceive: ${intent.contentToString()}")

		val lapisBt = (context.applicationContext as Application).lapisBt

		val value: String = when (intent.action) {
			"get-state" -> {
				lapisBt.state.value.toString()
			}
			"get-bluetoothName" -> {
				lapisBt.bluetoothDeviceName.value.toString()
			}
			"get-isScanning" -> {
				lapisBt.isScanning.value.toString()
			}
			"get-activeBluetoothServersUuids" -> {
				lapisBt.activeBluetoothServersUuids.value.toJson()
			}
			"get-scannedDevices" -> {
				lapisBt.scannedDevices.value.toJson()
			}
			"get-pairedDevices" -> {
				lapisBt.pairedDevices.value.toJson()
			}
			"get-remoteDevice" -> {
				val address = intent.getStringExtra("address")!!
				lapisBt.getRemoteDevice(address)?.toJson() ?: "null"
			}
			"receive-data" -> {
				val address = intent.getStringExtra("address")!!
				val bytesLength = intent.getIntExtra("bytesLength", -1)
				require(bytesLength > 0)

				var byteArray = byteArrayOf()
				runBlocking {
					lapisBt.receiveData(address) { stream ->
						val dataStream = DataInputStream(stream)
						byteArray = dataStream.readNBytes2(bytesLength).also {
							Log.i(TAG, "readNBytes2: ${it.contentToString()}")
						}
					}
				}
				byteArray.toJson()
			}
			else -> throw UnsupportedOperationException("The action '${intent.action}' is not supported.")
		}

		setResult(Activity.RESULT_OK, value, Bundle.EMPTY)

		Log.i(TAG, "END onReceive: action: ${intent.action}, resultCode: $resultCode, resultData: $resultData")
	}


	companion object {
		const val TAG = "TestingBroadcastReceiver"
	}
}


fun InputStream.readNBytes2(len: Int): ByteArray {
	require(len >= 0) { "len < 0" }

	var bufs: MutableList<ByteArray>? = null
	var result: ByteArray? = null
	var total = 0
	var remaining = len
	var n: Int
	do {
		var buf = ByteArray(min(remaining, /* InputStream.DEFAULT_BUFFER_SIZE */ 8192))
		var nread = 0

		// read to EOF which may read more or less than buffer size
		while ((read(
				buf, nread,
				min(buf.size - nread, remaining)
			).also { n = it }) > 0
		) {
			nread += n
			remaining -= n
		}

		if (nread > 0) {
			if (/* MAX_BUFFER_SIZE */ (Int.MAX_VALUE - 8) - total < nread) {
				throw OutOfMemoryError("Required array size too large")
			}
			if (nread < buf.size) {
				buf = buf.copyOfRange(0, nread)
			}
			total += nread
			if (result == null) {
				result = buf
			}
			else {
				if (bufs == null) {
					bufs = ArrayList<ByteArray>()
					bufs.add(result)
				}
				bufs.add(buf)
			}
		}
		// if the last call to read returned -1 or the number of bytes
		// requested have been read then break
	}
	while (n >= 0 && remaining > 0)

	if (bufs == null) {
		if (result == null) {
			return ByteArray(0)
		}
		return if (result.size == total) result else result.copyOf(total)
	}

	result = ByteArray(total)
	var offset = 0
	remaining = total
	for (b in bufs) {
		val count = min(b.size, remaining)
		System.arraycopy(b, 0, result, offset, count)
		offset += count
		remaining -= count
	}

	return result
}
