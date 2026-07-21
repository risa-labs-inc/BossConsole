package ai.rever.boss.utils

import kotlinx.coroutines.flow.StateFlow

/**
 * Multiplatform deep link handler interface
 */
expect object DeepLinkHandler {
    /**
     * Flow that emits deep link URIs when they are received
     */
    val deepLinkFlow: StateFlow<String?>
    
    /**
     * Process a deep link URI
     */
    fun processDeepLink(uri: String)
    
    /**
     * Clear the current deep link
     */
    fun clearDeepLink()
    
    /**
     * Extract verification token from a deep link URI
     */
    fun extractVerificationToken(uri: String): String?
    
    /**
     * Extract verification type from a deep link URI (signup, recovery, etc.)
     */
    fun extractVerificationType(uri: String): String?
}
