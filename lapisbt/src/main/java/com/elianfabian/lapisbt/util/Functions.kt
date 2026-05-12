package com.elianfabian.lapisbt.util

internal fun checkBluetoothAddressInternal(address: String?): Boolean {
	val addressLength = 17

	if (address == null || address.length != addressLength) {
		return false
	}
	for (i in 0..<addressLength) {
		val c = address[i]
		when (i % 3) {
			0, 1 -> {
				if ((c in '0'..'9') || (c in 'A'..'F')) {
					// hex character, OK
					break
				}
				return false
			}
			2 -> {
				if (c == ':') {
					break // OK
				}
				return false
			}
		}
	}
	return true
}
