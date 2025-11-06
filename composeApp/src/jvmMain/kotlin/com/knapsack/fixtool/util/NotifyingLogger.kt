package com.knapsack.fixtool.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A logger wrapper that both logs messages and optionally notifies the user via callback.
 * This ensures errors are not silently suppressed and users are informed of issues.
 *
 * @param clazz The class this logger is for (used to get the proper logger name)
 * @param onNotify Optional callback to show notifications to the user (e.g., error popups)
 */
class NotifyingLogger(
    clazz: Class<*>,
    private val onNotify: ((String) -> Unit)? = null,
) {
    private val logger: Logger = LoggerFactory.getLogger(clazz)

    /**
     * Log an error with exception and optionally notify the user
     */
    fun error(message: String, throwable: Throwable? = null, notifyUser: Boolean = false) {
        if (throwable != null) {
            logger.error(message, throwable)
        } else {
            logger.error(message)
        }

        if (notifyUser && onNotify != null) {
            onNotify(message)
        }
    }

    /**
     * Log a warning and optionally notify the user
     */
    fun warn(message: String, throwable: Throwable? = null, notifyUser: Boolean = false) {
        if (throwable != null) {
            logger.warn(message, throwable)
        } else {
            logger.warn(message)
        }

        if (notifyUser && onNotify != null) {
            onNotify(message)
        }
    }

    /**
     * Log info message (typically not shown to user)
     */
    fun info(message: String, vararg args: Any?) {
        logger.info(message, *args)
    }

    /**
     * Log debug message (typically not shown to user)
     */
    fun debug(message: String, vararg args: Any?) {
        logger.debug(message, *args)
    }

    /**
     * Log warning message (SLF4J-style parameterized)
     */
    fun warn(message: String, vararg args: Any?) {
        logger.warn(message, *args)
    }

    companion object {
        /**
         * Create a NotifyingLogger for a Kotlin class
         */
        inline fun <reified T> create(
            noinline onNotify: ((String) -> Unit)? = null,
        ): NotifyingLogger = NotifyingLogger(T::class.java, onNotify)
    }
}
