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
		if (enabled && minLevel.value <= LapisLogger.Level.Verbose.value) {
			Log.v(tag, message)
		}
	}

	override fun debug(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Debug.value) {
			Log.d(tag, message)
		}
	}

	override fun info(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Info.value) {
			Log.i(tag, message)
		}
	}

	override fun warning(tag: String, message: String) {
		if (enabled && minLevel.value <= LapisLogger.Level.Warn.value) {
			Log.w(tag, message)
		}
	}

	override fun error(tag: String, message: String, throwable: Throwable?) {
		if (enabled && minLevel.value <= LapisLogger.Level.Error.value) {
			Log.e(tag, message, throwable)
		}
	}
}
