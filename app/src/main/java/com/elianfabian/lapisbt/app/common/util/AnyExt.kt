@file:Suppress("DeprecatedCallableAddReplaceWith")

package com.elianfabian.lapisbt.app.common.util


@Deprecated("Marked as deprecated to remember that when used it should be not be committed.")
inline fun <T> T.printlnSelf(
	prefix: String = "",
	suffix: String = "",
	block: (T) -> Any? = { it },
) = also {
	println("$prefix${block(this)}$suffix")
}

/**
 * Use to avoid the use of many brackets when casting multiple times.
 */
@Suppress("NOTHING_TO_INLINE", "UNCHECKED_CAST")
inline fun <T> Any?.asInstanceOf(): T = this as T

/**
 * Use to avoid the use of many brackets when casting multiple times.
 */
@Suppress("NOTHING_TO_INLINE", "UNCHECKED_CAST")
inline fun <T> Any?.asInstanceOfOrNull(): T? = this as? T
