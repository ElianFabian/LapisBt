package com.elianfabian.lapisbt.util

import android.util.Log

/**
 * Configuration for LapisBt logging.
 */
public interface LapisLogConfig {

	public var enabled: Boolean
	public var minLevel: LapisLogger.Level
}

/**
 * A simple logging abstraction for LapisBt.
 */
public interface LapisLogger : LapisLogConfig {

	public fun verbose(tag: String, message: String)
	public fun debug(tag: String, message: String)
	public fun info(tag: String, message: String)
	public fun warning(tag: String, message: String)
	public fun error(tag: String, message: String, throwable: Throwable? = null)

	public companion object {
		/**
		 * A [LapisLogger] that uses the standard Android [Log] class.
		 */
		public fun android(
			enabled: Boolean = true,
			minLevel: Level = Level.Info,
		): LapisLogger = AndroidLogger(enabled, minLevel)

		/**
		 * A [LapisLogger] that logs to the console using println.
		 * Useful for unit tests where Android's Log class is not available.
		 */
		public fun console(
			enabled: Boolean = true,
			minLevel: Level = Level.Verbose,
			prefix: String = "",
		): LapisLogger = ConsoleLogger(
			enabled = enabled,
			minLevel = minLevel,
			prefix = prefix,
		)

		/**
		 * A [LapisLogger] that does nothing.
		 */
		public val Silent: LapisLogger = object : LapisLogger {
			override var enabled: Boolean = false
			override var minLevel: Level = Level.Error
			override fun verbose(tag: String, message: String) {}
			override fun debug(tag: String, message: String) {}
			override fun info(tag: String, message: String) {}
			override fun warning(tag: String, message: String) {}
			override fun error(tag: String, message: String, throwable: Throwable?) {}
		}

		public inline fun LapisLogger.verbose(tag: String, message: () -> String) {
			if (enabled && minLevel <= Level.Verbose) {
				verbose(tag, message())
			}
		}

		public inline fun LapisLogger.debug(tag: String, message: () -> String) {
			if (enabled && minLevel <= Level.Debug) {
				debug(tag, message())
			}
		}

		public inline fun LapisLogger.info(tag: String, message: () -> String) {
			if (enabled && minLevel <= Level.Info) {
				info(tag, message())
			}
		}

		public inline fun LapisLogger.warning(tag: String, message: () -> String) {
			if (enabled && minLevel <= Level.Warn) {
				warning(tag, message())
			}
		}

		public inline fun LapisLogger.error(tag: String, throwable: Throwable? = null, message: () -> String) {
			if (enabled && minLevel <= Level.Error) {
				error(tag, message(), throwable)
			}
		}
	}

	public enum class Level(public val value: Int) {
		Verbose(2),
		Debug(3),
		Info(4),
		Warn(5),
		Error(6);
	}
}

internal class AndroidLogger(
	override var enabled: Boolean,
	override var minLevel: LapisLogger.Level,
) : LapisLogger {

	override fun verbose(tag: String, message: String) {
		if (enabled && minLevel <= LapisLogger.Level.Verbose) {
			Log.v(tag, message)
		}
	}

	override fun debug(tag: String, message: String) {
		if (enabled && minLevel <= LapisLogger.Level.Debug) {
			Log.d(tag, message)
		}
	}

	override fun info(tag: String, message: String) {
		if (enabled && minLevel <= LapisLogger.Level.Info) {
			Log.i(tag, message)
		}
	}

	override fun warning(tag: String, message: String) {
		if (enabled && minLevel <= LapisLogger.Level.Warn) {
			Log.w(tag, message)
		}
	}

	override fun error(tag: String, message: String, throwable: Throwable?) {
		if (enabled && minLevel <= LapisLogger.Level.Error) {
			Log.e(tag, message, throwable)
		}
	}
}


internal class ConsoleLogger(
	override var enabled: Boolean,
	override var minLevel: LapisLogger.Level,
	val prefix: String,
) : LapisLogger {

	// ANSI Escape Codes for Colors
	private companion object {
		const val RESET = "\u001B[0m"
		const val GRAY = "\u001B[90m"    // Verbose
		const val CYAN = "\u001B[36m"    // Debug
		const val GREEN = "\u001B[32m"   // Info
		const val YELLOW = "\u001B[33m"  // Warning
		const val RED = "\u001B[31m"     // Error
	}

	override fun verbose(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Verbose.value) {
			val actualPrefix = if (prefix.isNotBlank()) {
				"$prefix|"
			}
			else ""
			println("${GRAY}V/$actualPrefix$tag: $message$RESET")
		}
	}

	override fun debug(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Debug.value) {
			val actualPrefix = if (prefix.isNotBlank()) {
				"$prefix|"
			}
			else ""
			println("${CYAN}D/$actualPrefix$tag: $message$RESET")
		}
	}

	override fun info(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Info.value) {
			val actualPrefix = if (prefix.isNotBlank()) {
				"$prefix|"
			}
			else ""
			println("${GREEN}I/$actualPrefix$tag: $message$RESET")
		}
	}

	override fun warning(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Warn.value) {
			val actualPrefix = if (prefix.isNotBlank()) {
				"$prefix|"
			}
			else ""
			println("${YELLOW}W/$actualPrefix$tag: $message$RESET")
		}
	}

	override fun error(tag: String, message: String, throwable: Throwable?) {
		if (enabled && minLevel.value <= LapisLogger.Level.Error.value) {
			val actualPrefix = if (prefix.isNotBlank()) {
				"$prefix|"
			}
			else ""
			println("${RED}E/$actualPrefix$tag: $message$RESET")
			throwable?.printStackTrace()
		}
	}
}
