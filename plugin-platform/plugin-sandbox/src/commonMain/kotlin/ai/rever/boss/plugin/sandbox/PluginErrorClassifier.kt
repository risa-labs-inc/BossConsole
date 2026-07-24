package ai.rever.boss.plugin.sandbox

/**
 * Classifies plugin errors to determine if they are deterministic (binary incompatibility)
 * or potentially recoverable.
 *
 * Binary incompatibility errors (e.g., [NoSuchMethodError], [NoClassDefFoundError]) occur when
 * a plugin was compiled against a newer host API than what's currently running. These errors are
 * deterministic — restarting the plugin will never fix them. The plugin needs an update.
 */
object PluginErrorClassifier {
    enum class ErrorCategory {
        /** Non-recoverable linkage errors from API version mismatch. */
        BINARY_INCOMPATIBILITY,

        /** Potentially transient errors that may resolve on restart. */
        RECOVERABLE,
    }

    /**
     * Classify an error by inspecting the throwable and its cause chain.
     *
     * Binary incompatibility errors include:
     * - [NoSuchMethodError] — method removed or signature changed
     * - [NoClassDefFoundError] — class removed or relocated
     * - [IncompatibleClassChangeError] — class hierarchy changed
     * - [AbstractMethodError] — abstract method not implemented
     * - [NoSuchFieldError] — field removed or renamed
     * - [IllegalAccessError] — visibility changed
     * - [UnsupportedClassVersionError] — compiled for newer JVM
     * - [UnsatisfiedLinkError] — native library mismatch (e.g., JxBrowser version change)
     */
    fun classify(error: Throwable): ErrorCategory =
        when (error) {
            is NoSuchMethodError,
            is NoClassDefFoundError,
            is IncompatibleClassChangeError,
            is AbstractMethodError,
            is NoSuchFieldError,
            is IllegalAccessError,
            is UnsupportedClassVersionError,
            is UnsatisfiedLinkError,
            -> {
                ErrorCategory.BINARY_INCOMPATIBILITY
            }

            else -> {
                // Check the cause chain — binary incompatibility may be wrapped
                val cause = error.cause
                if (cause != null && cause !== error) {
                    classify(cause)
                } else {
                    ErrorCategory.RECOVERABLE
                }
            }
        }

    /** Convenience check for binary incompatibility errors. */
    fun isBinaryIncompatibility(error: Throwable): Boolean = classify(error) == ErrorCategory.BINARY_INCOMPATIBILITY
}
