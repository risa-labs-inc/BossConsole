package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Detects form fields in browser pages for secret auto-fill integration.
 *
 * This detector injects JavaScript into the browser to:
 * - Identify focused form fields (input, textarea)
 * - Determine field type (username, password, email)
 * - Extract field metadata (name, id, placeholder)
 * - Find related form action URL
 *
 * Used by Issue #56 - Secret Access Integration with Fluck Browser
 */
object FormFieldDetector {

    private val logger = BossLogger.forComponent("FormFieldDetector")

    /**
     * Represents a form field detected in the browser
     */
    data class FormFieldInfo(
        val fieldType: FieldType,
        val fieldName: String,
        val fieldId: String,
        val fieldPlaceholder: String,
        val fieldValue: String,
        val parentFormAction: String?,
        val inputType: String,
        val autocomplete: String
    ) {
        fun isPasswordField(): Boolean = fieldType == FieldType.PASSWORD
        fun isUsernameField(): Boolean = fieldType == FieldType.USERNAME || fieldType == FieldType.EMAIL
    }

    /**
     * Types of form fields we can detect
     */
    enum class FieldType {
        USERNAME,   // Username or login field
        PASSWORD,   // Password field
        EMAIL,      // Email field
        TEXT,       // Generic text field
        UNKNOWN     // Cannot determine
    }

    /**
     * Inject form field detection script into the browser.
     *
     * This script runs in the page context and sets up event listeners
     * to track focused fields and provide field information on demand.
     *
     * @param browser The LockedBrowser instance (thread-safe wrapper)
     */
    fun injectFormDetectionScript(browser: LockedBrowser) {
        try {
            val script = """
                (function() {
                    // Store currently focused element
                    window.__BOSS_FOCUSED_FIELD = null;

                    // Track focus events
                    document.addEventListener('focusin', function(e) {
                        if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) {
                            window.__BOSS_FOCUSED_FIELD = e.target;
                            console.log('[BOSS] Field focused:', e.target.type, e.target.name || e.target.id);
                        }
                    }, true);

                    // Track focus loss
                    document.addEventListener('focusout', function(e) {
                        // Keep reference for a short time after blur (for context menu)
                        setTimeout(function() {
                            if (document.activeElement.tagName !== 'INPUT' &&
                                document.activeElement.tagName !== 'TEXTAREA') {
                                window.__BOSS_FOCUSED_FIELD = null;
                            }
                        }, 500);
                    }, true);

                    // Function to get focused field info
                    window.__BOSS_GET_FOCUSED_FIELD = function() {
                        const field = window.__BOSS_FOCUSED_FIELD || document.activeElement;

                        if (!field || (field.tagName !== 'INPUT' && field.tagName !== 'TEXTAREA')) {
                            return null;
                        }

                        // Find parent form
                        let form = field.closest('form');
                        let formAction = form ? form.action : null;

                        return {
                            type: field.type || 'text',
                            name: field.name || '',
                            id: field.id || '',
                            placeholder: field.placeholder || '',
                            value: field.value || '',
                            formAction: formAction || '',
                            autocomplete: field.getAttribute('autocomplete') || '',
                            className: field.className || '',
                            ariaLabel: field.getAttribute('aria-label') || ''
                        };
                    };

                    console.log('[BOSS] Form field detection script injected');
                })();
            """.trimIndent()

            browser.mainFrame().ifPresent { frame ->
                frame.executeJavaScript<Any>(script)
            }
        } catch (e: Exception) {
            // Detection script injection failed - auto-fill just won't offer for this page
            logger.debug(
                LogCategory.BROWSER,
                "Form field detection script injection failed",
                mapOf("error" to e.toString()),
            )
        }
    }

    /**
     * Get information about the currently focused form field.
     *
     * @param browser The LockedBrowser instance (thread-safe wrapper)
     * @return FormFieldInfo if a field is focused, null otherwise
     */
    suspend fun getCurrentFocusedField(browser: LockedBrowser): FormFieldInfo? {
        return try {
            val result = CompletableDeferred<FormFieldInfo?>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    // Call JavaScript function to get field info - return as JSON string
                    val value = frame.executeJavaScript<String>(
                        "JSON.stringify(window.__BOSS_GET_FOCUSED_FIELD ? window.__BOSS_GET_FOCUSED_FIELD() : null)"
                    )

                    value?.let { jsonString ->
                        if (jsonString != "null" && jsonString.isNotEmpty()) {
                            // Parse JSON string
                            val fieldInfo = parseFieldInfoJson(jsonString)
                            result.complete(fieldInfo)
                        } else {
                            result.complete(null)
                        }
                    } ?: result.complete(null)
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Focused-field JS query failed - reporting no field",
                        mapOf("error" to e.toString()),
                    )
                    result.complete(null)
                }
            }

            // Wait for result with timeout
            withTimeout(2.seconds) {
                result.await()
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Focused-field detection timed out or failed",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Parse field information from JSON string.
     *
     * JavaScript returns JSON like:
     * {"type":"password","name":"pwd","id":"password-field",...}
     */
    private fun parseFieldInfoJson(jsonString: String): FormFieldInfo? {
        return try {
            // Simple JSON parsing - extract values between quotes
            val extractValue = { key: String ->
                val pattern = "\"$key\":\"([^\"]*)\""
                val regex = Regex(pattern)
                regex.find(jsonString)?.groupValues?.getOrNull(1) ?: ""
            }

            val inputType = extractValue("type").ifEmpty { "text" }
            val fieldName = extractValue("name")
            val fieldId = extractValue("id")
            val placeholder = extractValue("placeholder")
            val value = extractValue("value")
            val formAction = extractValue("formAction").ifEmpty { null }
            val autocomplete = extractValue("autocomplete")
            val className = extractValue("className")
            val ariaLabel = extractValue("ariaLabel")

            // Determine field type using heuristics
            val fieldType = determineFieldType(
                inputType = inputType,
                fieldName = fieldName,
                fieldId = fieldId,
                placeholder = placeholder,
                autocomplete = autocomplete,
                className = className,
                ariaLabel = ariaLabel
            )

            FormFieldInfo(
                fieldType = fieldType,
                fieldName = fieldName,
                fieldId = fieldId,
                fieldPlaceholder = placeholder,
                fieldValue = value,
                parentFormAction = formAction,
                inputType = inputType,
                autocomplete = autocomplete
            )
        } catch (e: Exception) {
            // Never log the JSON payload itself - it can contain a typed field value.
            // (e.toString() is safe here: this parser is regex/string-based, so its
            // exceptions never embed the input, unlike kotlinx.serialization's.)
            logger.debug(
                LogCategory.BROWSER,
                "Failed to parse field info JSON - no field detected",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Parse field information from JavaScript object string (legacy).
     *
     * JavaScript returns an object like:
     * {type: "password", name: "pwd", id: "password-field", ...}
     */
    private fun parseFieldInfo(jsObjectString: String): FormFieldInfo? {
        return try {
            // Simple parsing of JavaScript object string
            // Format: {key: value, key: value, ...}
            val cleanStr = jsObjectString.trim().removeSurrounding("{", "}")
            val pairs = cleanStr.split(", ")
            val map = mutableMapOf<String, String>()

            pairs.forEach { pair ->
                val parts = pair.split(": ", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().removeSurrounding("\"")
                    map[key] = value
                }
            }

            val inputType = map["type"] ?: "text"
            val fieldName = map["name"] ?: ""
            val fieldId = map["id"] ?: ""
            val placeholder = map["placeholder"] ?: ""
            val value = map["value"] ?: ""
            val formAction = map["formAction"]
            val autocomplete = map["autocomplete"] ?: ""
            val className = map["className"] ?: ""
            val ariaLabel = map["ariaLabel"] ?: ""

            // Determine field type using heuristics
            val fieldType = determineFieldType(
                inputType = inputType,
                fieldName = fieldName,
                fieldId = fieldId,
                placeholder = placeholder,
                autocomplete = autocomplete,
                className = className,
                ariaLabel = ariaLabel
            )

            FormFieldInfo(
                fieldType = fieldType,
                fieldName = fieldName,
                fieldId = fieldId,
                fieldPlaceholder = placeholder,
                fieldValue = value,
                parentFormAction = formAction,
                inputType = inputType,
                autocomplete = autocomplete
            )
        } catch (e: Exception) {
            // Never log the object string itself - it can contain a typed field value.
            // (e.toString() is safe here: string-based parsing, exceptions never embed input.)
            logger.debug(
                LogCategory.BROWSER,
                "Failed to parse legacy field info - no field detected",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Determine field type using multiple heuristics.
     *
     * Checks:
     * 1. Input type attribute (type="password", type="email")
     * 2. Autocomplete attribute (autocomplete="username", "current-password")
     * 3. Field name/id patterns (contains "user", "login", "pass", "email")
     * 4. Placeholder text
     * 5. Class names
     * 6. ARIA labels
     */
    private fun determineFieldType(
        inputType: String,
        fieldName: String,
        fieldId: String,
        placeholder: String,
        autocomplete: String,
        className: String,
        ariaLabel: String
    ): FieldType {
        val lowerType = inputType.lowercase()
        val lowerName = fieldName.lowercase()
        val lowerId = fieldId.lowercase()
        val lowerPlaceholder = placeholder.lowercase()
        val lowerAutocomplete = autocomplete.lowercase()
        val lowerClassName = className.lowercase()
        val lowerAriaLabel = ariaLabel.lowercase()

        // 1. Check input type first (most reliable)
        when (lowerType) {
            "password" -> return FieldType.PASSWORD
            "email" -> return FieldType.EMAIL
        }

        // 2. Check autocomplete attribute (HTML5 standard)
        when {
            lowerAutocomplete.contains("username") -> return FieldType.USERNAME
            lowerAutocomplete.contains("email") -> return FieldType.EMAIL
            lowerAutocomplete.contains("password") ||
            lowerAutocomplete.contains("current-password") ||
            lowerAutocomplete.contains("new-password") -> return FieldType.PASSWORD
        }

        // 3. Check field name/id patterns
        val combinedText = "$lowerName $lowerId $lowerPlaceholder $lowerClassName $lowerAriaLabel"

        when {
            combinedText.contains("password") || combinedText.contains("passwd") ||
            combinedText.contains("pwd") -> return FieldType.PASSWORD

            combinedText.contains("email") || combinedText.contains("e-mail") ||
            combinedText.contains("@") -> return FieldType.EMAIL

            combinedText.contains("username") || combinedText.contains("user") ||
            combinedText.contains("login") || combinedText.contains("account") ||
            combinedText.contains("userid") -> return FieldType.USERNAME
        }

        // 4. Default to TEXT for regular input fields
        return if (lowerType == "text" || lowerType.isEmpty()) {
            FieldType.TEXT
        } else {
            FieldType.UNKNOWN
        }
    }

    /**
     * Find all input fields in the current page (for comprehensive detection).
     *
     * Useful for finding username field when only password field is focused.
     *
     * @param browser The LockedBrowser instance (thread-safe wrapper)
     * @return List of all form fields found
     */
    suspend fun findAllFormFields(browser: LockedBrowser): List<FormFieldInfo> {
        return try {
            val fields = mutableListOf<FormFieldInfo>()
            val result = CompletableDeferred<List<FormFieldInfo>>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    val script = """
                        (function() {
                            const inputs = document.querySelectorAll('input, textarea');
                            return Array.from(inputs).map(field => ({
                                type: field.type || 'text',
                                name: field.name || '',
                                id: field.id || '',
                                placeholder: field.placeholder || '',
                                autocomplete: field.getAttribute('autocomplete') || ''
                            }));
                        })();
                    """.trimIndent()

                    frame.executeJavaScript<Any>(script)
                    // Parse result array (simplified)
                    result.complete(fields)
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Form fields JS enumeration failed - reporting none",
                        mapOf("error" to e.toString()),
                    )
                    result.complete(emptyList())
                }
            }

            withTimeout(2.seconds) {
                result.await()
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Form field enumeration timed out or failed",
                mapOf("error" to e.toString()),
            )
            emptyList()
        }
    }
}
