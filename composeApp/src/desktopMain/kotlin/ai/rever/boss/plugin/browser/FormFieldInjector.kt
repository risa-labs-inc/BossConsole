package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Injects secret values into browser form fields for auto-fill.
 *
 * This injector uses JavaScript execution to:
 * - Fill specific fields by reference
 * - Find and fill username/password fields intelligently
 * - Trigger input events for framework compatibility (React, Vue, Angular)
 * - Handle different field types (input, textarea)
 *
 * Used by Issue #56 - Secret Access Integration with Fluck Browser
 */
object FormFieldInjector {
    private val logger = BossLogger.forComponent("FormFieldInjector")

    /**
     * Fill modes for credential injection
     */
    enum class FillMode {
        USERNAME_ONLY,      // Fill only username/email field
        PASSWORD_ONLY,      // Fill only password field
        BOTH,              // Fill both username and password (recommended)
        COPY_USERNAME,     // Copy username to clipboard
        COPY_PASSWORD      // Copy password to clipboard
    }

    /**
     * Result of fill operation
     */
    sealed class FillResult {
        data class Success(val message: String) : FillResult()
        data class PartialSuccess(val message: String) : FillResult()
        data class Error(val message: String) : FillResult()
    }

    /**
     * Debug logger for form field operations.
     * Logs field state, attributes, and context for troubleshooting autofill issues.
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     * @param phase Debug phase (e.g., "BEFORE_FILL", "AFTER_FILL", "ON_SUBMIT")
     * @param fieldIdentifier Identifier for the field (default: activeElement)
     * @return JSON string with field debug information
     */
    private suspend fun logFieldDebugInfo(
        browser: LockedBrowser,
        phase: String,
        fieldIdentifier: String = "activeElement"
    ): String {
        return try {
            val result = CompletableDeferred<String>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    val debugInfo = frame.executeJavaScript<String>("""
                        (function() {
                            const field = document.activeElement;
                            if (!field || (field.tagName !== 'INPUT' && field.tagName !== 'TEXTAREA')) {
                                return 'NO_FIELD_FOCUSED';
                            }

                            const info = {
                                phase: '$phase',
                                tagName: field.tagName,
                                type: field.type || 'N/A',
                                name: field.name || 'N/A',
                                id: field.id || 'N/A',
                                value: field.value ? `'${'$'}{field.value}' (length: ${'$'}{field.value.length})` : 'EMPTY',
                                placeholder: field.placeholder || 'N/A',
                                readonly: field.readOnly,
                                disabled: field.disabled,
                                autocomplete: field.autocomplete || 'N/A',
                                hasForm: field.form ? 'YES' : 'NO',
                                formAction: field.form?.action || 'N/A',
                                classList: Array.from(field.classList).join(', ') || 'NONE',
                                bossFilled: field.getAttribute('data-boss-filled') || 'NO'
                            };

                            return JSON.stringify(info, null, 2);
                        })();
                    """.trimIndent())

                    result.complete(debugInfo ?: "NULL_RESPONSE")
                } catch (e: Exception) {
                    result.complete("EXCEPTION: ${e.message}")
                }
            }

            withTimeout(2.seconds) {
                result.await()
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Fill a specific form field with a value.
     *
     * Enhanced with comprehensive debugging and improved event sequence to
     * properly trigger modern framework (React/Vue/Angular) change detection.
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     * @param fieldInfo Information about the field to fill
     * @param value Value to inject into the field
     * @return FillResult indicating success or failure
     */
    suspend fun fillField(
        browser: LockedBrowser,
        fieldInfo: FormFieldDetector.FormFieldInfo,
        value: String
    ): FillResult {
        return try {
            val result = CompletableDeferred<FillResult>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    // Escape special characters in value for JavaScript
                    val escapedValue = value.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")

                    val script = """
                        (function() {
                            const field = window.__BOSS_FOCUSED_FIELD || document.activeElement;

                            if (!field || (field.tagName !== 'INPUT' && field.tagName !== 'TEXTAREA')) {
                                console.error('[BOSS] No valid field focused');
                                return 'ERROR: No field focused';
                            }

                            console.log('[BOSS] Starting fill sequence for:', field.name || field.id || 'unnamed');
                            console.log('[BOSS] Initial value:', field.value);

                            // Step 1: Ensure field is focused
                            if (document.activeElement !== field) {
                                console.log('[BOSS] Step 1: Focusing field...');
                                field.focus();
                            } else {
                                console.log('[BOSS] Step 1: Field already focused');
                            }

                            // Step 2: Dispatch focus event (in case programmatic focus didn't trigger it)
                            console.log('[BOSS] Step 2: Dispatching focus event...');
                            field.dispatchEvent(new FocusEvent('focus', { bubbles: true }));

                            // Small delay to let framework process focus
                            setTimeout(() => {
                                console.log('[BOSS] Step 3: Beginning value update...');

                                // Step 3: Simulate keydown (user starts typing)
                                const keydownEvent = new KeyboardEvent('keydown', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                });
                                field.dispatchEvent(keydownEvent);
                                console.log('[BOSS] Step 3a: keydown dispatched');

                                // Step 4: Set value using React-compatible method
                                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                                    window.HTMLInputElement.prototype, 'value'
                                ).set;
                                nativeInputValueSetter.call(field, '$escapedValue');
                                console.log('[BOSS] Step 4: Value set via native setter');

                                // Step 5: Dispatch input event (React listens to this)
                                const inputEvent = new Event('input', { bubbles: true });
                                field.dispatchEvent(inputEvent);
                                console.log('[BOSS] Step 5: input event dispatched');

                                // Step 6: Simulate keyup (user finishes typing)
                                const keyupEvent = new KeyboardEvent('keyup', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                });
                                field.dispatchEvent(keyupEvent);
                                console.log('[BOSS] Step 6: keyup dispatched');

                                // Small delay before blur
                                setTimeout(() => {
                                    console.log('[BOSS] Step 7: Dispatching blur...');

                                    // Step 7: Blur field (triggers validation in many frameworks)
                                    field.dispatchEvent(new FocusEvent('blur', { bubbles: true }));
                                    console.log('[BOSS] Step 7a: blur event dispatched');

                                    // Step 8: Change event (final commit)
                                    const changeEvent = new Event('change', { bubbles: true });
                                    field.dispatchEvent(changeEvent);
                                    console.log('[BOSS] Step 8: change event dispatched');

                                    console.log('[BOSS] Fill sequence complete. Final value:', field.value);

                                    // Mark field as BOSS-filled for monitoring
                                    field.setAttribute('data-boss-filled', 'true');
                                    field.setAttribute('data-boss-filled-at', new Date().toISOString());
                                }, 50); // 50ms delay before blur

                            }, 50); // 50ms delay after focus

                            return 'SUCCESS';
                        })();
                    """.trimIndent()

                    val outcome = frame.executeJavaScript<String>(script)

                    result.complete(
                        if (outcome?.contains("SUCCESS", ignoreCase = false) == true) {
                            FillResult.Success("Field filled successfully")
                        } else {
                            FillResult.Error("Failed to fill field: $outcome")
                        }
                    )
                } catch (e: Exception) {
                    result.complete(FillResult.Error("Exception: ${e.message}"))
                }
            }

            val fillResult = withTimeout(5.seconds) {  // Increased timeout for async operations
                result.await()
            }

            // Wait for async operations to complete (frameworks may update state asynchronously)
            kotlinx.coroutines.delay(200)

            fillResult
        } catch (e: Exception) {
            FillResult.Error("Timeout or exception: ${e.message}")
        }
    }

    /**
     * Fill credentials (username and password) intelligently.
     *
     * Automatically finds username and password fields on the page
     * and fills them with provided values.
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     * @param username Username/email to fill
     * @param password Password to fill
     * @param mode Fill mode (both, username only, or password only)
     * @return FillResult indicating success or failure
     */
    suspend fun fillCredentials(
        browser: LockedBrowser,
        username: String,
        password: String,
        mode: FillMode = FillMode.BOTH
    ): FillResult {
        val result = when (mode) {
            FillMode.USERNAME_ONLY -> findAndFillUsername(browser, username)
            FillMode.PASSWORD_ONLY -> {
                val passwordResult = findAndFillPassword(browser, password)
                // Apply discrete mode if enabled and password was filled successfully
                if (passwordResult is FillResult.Success && BrowserSettings.discretePasswordFill) {
                    applyDiscreteMode(browser)
                }
                passwordResult
            }
            FillMode.BOTH -> {
                val usernameResult = findAndFillUsername(browser, username)
                val passwordResult = findAndFillPassword(browser, password)

                // Apply discrete mode if enabled and password was filled successfully
                if (passwordResult is FillResult.Success && BrowserSettings.discretePasswordFill) {
                    applyDiscreteMode(browser)
                }

                when {
                    usernameResult is FillResult.Success && passwordResult is FillResult.Success ->
                        FillResult.Success("✅ Username and password filled successfully")

                    usernameResult is FillResult.Success || passwordResult is FillResult.Success ->
                        FillResult.PartialSuccess("⚠️ Only one field filled successfully")

                    else -> FillResult.Error("❌ Failed to fill both fields")
                }
            }
            FillMode.COPY_USERNAME -> {
                copyToClipboard(username)
                FillResult.Success("Username copied to clipboard")
            }
            FillMode.COPY_PASSWORD -> {
                copyToClipboard(password)
                FillResult.Success("Password copied to clipboard")
            }
        }
        return result
    }

    /**
     * Find and fill username/email field.
     *
     * Uses multiple strategies to locate the username field:
     * 1. Look for focused field if it's username-like
     * 2. Find by autocomplete="username" or autocomplete="email"
     * 3. Find by input type="email"
     * 4. Find by field name/id containing "user", "login", "email"
     * 5. Find first text input in form
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     * @param username Username to fill
     * @return FillResult indicating success or failure
     */
    suspend fun findAndFillUsername(browser: LockedBrowser, username: String): FillResult {
        return try {
            val result = CompletableDeferred<FillResult>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    val escapedUsername = username.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")

                    val script = """
                        (function() {
                            console.log('[BOSS] 🔍 findAndFillUsername: Starting username field detection and fill');

                            // Helper function: Enhanced fill with proper event sequence for React compatibility
                            function enhancedFillField(field, value, fieldType) {
                                console.log('[BOSS] 📝 Filling ' + fieldType + ' field:', field.name || field.id || 'unnamed');
                                console.log('[BOSS] Pre-fill value:', field.value || 'EMPTY');

                                // Step 1: Ensure field is focused
                                if (document.activeElement !== field) {
                                    field.focus();
                                }
                                field.dispatchEvent(new FocusEvent('focus', { bubbles: true }));

                                // Step 2: Keydown event (simulate typing start)
                                field.dispatchEvent(new KeyboardEvent('keydown', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                }));

                                // Step 3: Set value using React-compatible native setter
                                // This is critical for React 16+ to detect the change
                                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                                    window.HTMLInputElement.prototype, 'value'
                                ).set;
                                nativeInputValueSetter.call(field, value);

                                // Step 4: Input event (React's onChange listens to this)
                                field.dispatchEvent(new Event('input', { bubbles: true }));

                                // Step 5: Keyup event (simulate typing end)
                                field.dispatchEvent(new KeyboardEvent('keyup', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                }));

                                // Step 6: Blur event (triggers validation)
                                field.dispatchEvent(new FocusEvent('blur', { bubbles: true }));

                                // Step 7: Change event (final commit)
                                field.dispatchEvent(new Event('change', { bubbles: true }));

                                // Mark field as BOSS-filled for debugging
                                field.setAttribute('data-boss-filled', 'true');
                                field.setAttribute('data-boss-filled-at', new Date().toISOString());
                                field.setAttribute('data-boss-field-type', fieldType);

                                console.log('[BOSS] ✅ Post-fill value:', field.value || 'EMPTY');
                                console.log('[BOSS] Field marked with data-boss-filled=true');
                            }

                            // Strategy 1: Check if focused field is username field
                            const focused = document.activeElement;
                            if (focused && focused.tagName === 'INPUT' &&
                                (focused.type === 'email' || focused.type === 'text')) {
                                const name = (focused.name || focused.id || '').toLowerCase();
                                if (name.includes('user') || name.includes('email') ||
                                    name.includes('login') || name.includes('account')) {
                                    console.log('[BOSS] Strategy 1: Using focused username field');
                                    enhancedFillField(focused, '$escapedUsername', 'username-focused');
                                    return 'SUCCESS: Filled focused username field';
                                }
                            }

                            // Strategy 2: Find by autocomplete attribute
                            let field = document.querySelector('[autocomplete="username"], [autocomplete="email"]');
                            if (field) {
                                console.log('[BOSS] Strategy 2: Found field by autocomplete attribute');
                                enhancedFillField(field, '$escapedUsername', 'username-autocomplete');
                                return 'SUCCESS: Filled username field (autocomplete)';
                            }

                            // Strategy 3: Find by type="email"
                            field = document.querySelector('input[type="email"]');
                            if (field) {
                                console.log('[BOSS] Strategy 3: Found field by type=email');
                                enhancedFillField(field, '$escapedUsername', 'username-email-type');
                                return 'SUCCESS: Filled email field';
                            }

                            // Strategy 4: Find by name/id containing keywords
                            const inputs = document.querySelectorAll('input[type="text"], input[type="email"]');
                            for (const input of inputs) {
                                const name = (input.name || input.id || '').toLowerCase();
                                if (name.includes('user') || name.includes('email') ||
                                    name.includes('login') || name.includes('account')) {
                                    console.log('[BOSS] Strategy 4: Found field by name/id keywords');
                                    enhancedFillField(input, '$escapedUsername', 'username-keyword');
                                    return 'SUCCESS: Filled username field (keyword match)';
                                }
                            }

                            // Strategy 5: Find first text input in form with password field
                            const forms = document.querySelectorAll('form');
                            for (const form of forms) {
                                const hasPassword = form.querySelector('input[type="password"]');
                                if (hasPassword) {
                                    field = form.querySelector('input[type="text"], input[type="email"]');
                                    if (field) {
                                        console.log('[BOSS] Strategy 5: Found first text field in form with password');
                                        enhancedFillField(field, '$escapedUsername', 'username-form-first');
                                        return 'SUCCESS: Filled username field (first in form)';
                                    }
                                }
                            }

                            console.log('[BOSS] ❌ ERROR: Username field not found with any strategy');
                            return 'ERROR: Username field not found';
                        })();
                    """.trimIndent()

                    val outcome = frame.executeJavaScript<String>(script)

                    result.complete(
                        if (outcome?.contains("SUCCESS", ignoreCase = false) == true) {
                            FillResult.Success("Username filled")
                        } else {
                            FillResult.Error("Username field not found")
                        }
                    )
                } catch (e: Exception) {
                    result.complete(FillResult.Error("Exception: ${e.message}"))
                }
            }

            withTimeout(2.seconds) {
                result.await()
            }
        } catch (e: Exception) {
            FillResult.Error("Failed to fill username: ${e.message}")
        }
    }

    /**
     * Find and fill password field.
     *
     * Uses multiple strategies to locate the password field:
     * 1. Look for focused field if it's password type
     * 2. Find by autocomplete="current-password"
     * 3. Find by input type="password"
     * 4. Find by field name/id containing "pass"
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     * @param password Password to fill
     * @return FillResult indicating success or failure
     */
    suspend fun findAndFillPassword(browser: LockedBrowser, password: String): FillResult {
        return try {
            val result = CompletableDeferred<FillResult>()

            browser.mainFrame().ifPresent { frame ->
                try {
                    val escapedPassword = password.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")

                    val script = """
                        (function() {
                            console.log('[BOSS] 🔍 findAndFillPassword: Starting password field detection and fill');

                            // Helper function: Enhanced fill with proper event sequence for React compatibility
                            function enhancedFillField(field, value, fieldType) {
                                console.log('[BOSS] 📝 Filling ' + fieldType + ' field:', field.name || field.id || 'unnamed');
                                console.log('[BOSS] Pre-fill value:', field.value ? '***' : 'EMPTY');

                                // Step 1: Ensure field is focused
                                if (document.activeElement !== field) {
                                    field.focus();
                                }
                                field.dispatchEvent(new FocusEvent('focus', { bubbles: true }));

                                // Step 2: Keydown event (simulate typing start)
                                field.dispatchEvent(new KeyboardEvent('keydown', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                }));

                                // Step 3: Set value using React-compatible native setter
                                // This is critical for React 16+ to detect the change
                                const nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                                    window.HTMLInputElement.prototype, 'value'
                                ).set;
                                nativeInputValueSetter.call(field, value);

                                // Step 4: Input event (React's onChange listens to this)
                                field.dispatchEvent(new Event('input', { bubbles: true }));

                                // Step 5: Keyup event (simulate typing end)
                                field.dispatchEvent(new KeyboardEvent('keyup', {
                                    bubbles: true,
                                    cancelable: true,
                                    key: 'Unidentified',
                                    code: 'Unidentified'
                                }));

                                // Step 6: Blur event (triggers validation)
                                field.dispatchEvent(new FocusEvent('blur', { bubbles: true }));

                                // Step 7: Change event (final commit)
                                field.dispatchEvent(new Event('change', { bubbles: true }));

                                // Mark field as BOSS-filled for debugging
                                field.setAttribute('data-boss-filled', 'true');
                                field.setAttribute('data-boss-filled-at', new Date().toISOString());
                                field.setAttribute('data-boss-field-type', fieldType);

                                console.log('[BOSS] ✅ Post-fill value:', field.value ? '***' : 'EMPTY');
                                console.log('[BOSS] Field marked with data-boss-filled=true');
                            }

                            // Strategy 1: Check if focused field is password field
                            const focused = document.activeElement;
                            if (focused && focused.tagName === 'INPUT' && focused.type === 'password') {
                                console.log('[BOSS] Strategy 1: Using focused password field');
                                enhancedFillField(focused, '$escapedPassword', 'password-focused');
                                return 'SUCCESS: Filled focused password field';
                            }

                            // Strategy 2: Find by autocomplete attribute
                            let field = document.querySelector('[autocomplete="current-password"]');
                            if (field) {
                                console.log('[BOSS] Strategy 2: Found field by autocomplete=current-password');
                                enhancedFillField(field, '$escapedPassword', 'password-autocomplete');
                                return 'SUCCESS: Filled password field (autocomplete)';
                            }

                            // Strategy 3: Find by type="password"
                            field = document.querySelector('input[type="password"]');
                            if (field) {
                                console.log('[BOSS] Strategy 3: Found field by type=password');
                                enhancedFillField(field, '$escapedPassword', 'password-type');
                                return 'SUCCESS: Filled password field (type)';
                            }

                            // Strategy 4: Find by name/id containing "pass" or "pwd"
                            const inputs = document.querySelectorAll('input[type="password"]');
                            for (const input of inputs) {
                                const name = (input.name || input.id || '').toLowerCase();
                                if (name.includes('pass') || name.includes('pwd')) {
                                    console.log('[BOSS] Strategy 4: Found field by name/id keywords');
                                    enhancedFillField(input, '$escapedPassword', 'password-keyword');
                                    return 'SUCCESS: Filled password field (keyword match)';
                                }
                            }

                            console.log('[BOSS] ❌ ERROR: Password field not found with any strategy');
                            return 'ERROR: Password field not found';
                        })();
                    """.trimIndent()

                    val outcome = frame.executeJavaScript<String>(script)

                    result.complete(
                        if (outcome?.contains("SUCCESS", ignoreCase = false) == true) {
                            FillResult.Success("Password filled")
                        } else {
                            FillResult.Error("Password field not found")
                        }
                    )
                } catch (e: Exception) {
                    result.complete(FillResult.Error("Exception: ${e.message}"))
                }
            }

            withTimeout(2.seconds) {
                result.await()
            }
        } catch (e: Exception) {
            FillResult.Error("Failed to fill password: ${e.message}")
        }
    }

    /**
     * Copy text to system clipboard.
     *
     * @param text Text to copy
     */
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val stringSelection = java.awt.datatransfer.StringSelection(text)
            clipboard.setContents(stringSelection, null)
        } catch (e: Exception) {
            // Clipboard copy failed - never log the text itself (may be a secret)
            logger.warn(LogCategory.BROWSER, "Failed to copy value to clipboard", error = e)
        }
    }

    /**
     * Apply discrete mode to password fields for privacy.
     *
     * When enabled, this function:
     * 1. Ensures password fields have type="password" (shows dots/asterisks)
     * 2. Applies a CSS blur effect (2px) for additional visual privacy
     * 3. Blur persists even when field is focused - maximum privacy
     * 4. Blur persists even if user clicks "show password" - prevents shoulder surfing
     *
     * Only affects fields marked with data-boss-filled="true".
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     */
    private suspend fun applyDiscreteMode(browser: LockedBrowser) {
        try {
            browser.mainFrame().ifPresent { frame ->
                frame.executeJavaScript<Unit>("""
                    (function() {
                        console.log('[BOSS] 🔒 Applying discrete mode to password fields');

                        // Helper function to apply discrete styling to a field
                        function applyDiscreteToField(field) {
                            // Skip if already discrete
                            if (field.getAttribute('data-boss-discrete') === 'true') {
                                console.log('[BOSS] Field already discrete, skipping');
                                return;
                            }

                            // Store original filter in case site applies its own
                            if (!field.hasAttribute('data-boss-original-filter')) {
                                field.setAttribute('data-boss-original-filter', field.style.filter || '');
                            }

                            // Apply blur effect (stays blurred always - even on focus or show password)
                            field.style.filter = 'blur(2px)';
                            field.style.webkitFilter = 'blur(2px)';
                            field.setAttribute('data-boss-discrete', 'true');

                            console.log('[BOSS] ✅ Discrete mode applied to field:', field.name || field.id || 'unnamed');

                            // Set up MutationObserver to maintain blur even on "show password" toggles
                            // This ensures maximum privacy - blur stays even if type changes to text
                            if (!field.__bossDiscreteObserver) {
                                const observer = new MutationObserver(mutations => {
                                    mutations.forEach(mutation => {
                                        if (mutation.type === 'attributes') {
                                            // Re-enforce blur if someone tries to remove it
                                            if (mutation.attributeName === 'style' || mutation.attributeName === 'type') {
                                                if (field.getAttribute('data-boss-discrete') === 'true') {
                                                    field.style.filter = 'blur(2px)';
                                                    field.style.webkitFilter = 'blur(2px)';
                                                }
                                            }
                                        }
                                    });
                                });

                                observer.observe(field, { attributes: true, attributeFilter: ['type', 'style'] });
                                field.__bossDiscreteObserver = observer;
                                console.log('[BOSS] MutationObserver attached to maintain blur');
                            }
                        }

                        // Find BOSS-filled password fields
                        const passwordFields = document.querySelectorAll('input[type="password"][data-boss-filled="true"]');
                        console.log('[BOSS] Found ' + passwordFields.length + ' BOSS-filled password field(s)');

                        passwordFields.forEach(applyDiscreteToField);
                    })();
                """.trimIndent())
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to apply discrete mode", error = e)
        }
    }

    /**
     * Remove discrete mode from all password fields.
     *
     * Called when user disables the discrete password fill setting to
     * immediately clear blur from any existing fields without requiring
     * a page reload.
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     */
    suspend fun removeDiscreteMode(browser: LockedBrowser) {
        try {
            browser.mainFrame().ifPresent { frame ->
                frame.executeJavaScript<Unit>("""
                    (function() {
                        console.log('[BOSS] 🔓 Removing discrete mode from password fields');

                        const discreteFields = document.querySelectorAll('[data-boss-discrete="true"]');
                        console.log('[BOSS] Found ' + discreteFields.length + ' discrete field(s) to clear');

                        discreteFields.forEach(field => {
                            // Disconnect observer first to prevent it from re-applying blur
                            if (field.__bossDiscreteObserver) {
                                field.__bossDiscreteObserver.disconnect();
                                delete field.__bossDiscreteObserver;
                                console.log('[BOSS] MutationObserver disconnected');
                            }

                            // Remove discrete marker
                            field.removeAttribute('data-boss-discrete');

                            // Restore original filter
                            const originalFilter = field.getAttribute('data-boss-original-filter') || '';
                            field.style.filter = originalFilter;
                            field.style.webkitFilter = originalFilter;
                            field.removeAttribute('data-boss-original-filter');

                            console.log('[BOSS] ✅ Discrete mode removed from field:', field.name || field.id || 'unnamed');
                        });
                    })();
                """.trimIndent())
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to remove discrete mode", error = e)
        }
    }

    /**
     * Install form submission monitor to debug what happens on Enter key press.
     * Monitors form submissions and field value changes to diagnose why autofilled
     * values might disappear.
     *
     * This helps track:
     * - Form submission events
     * - BOSS-filled field states during submission
     * - Enter key presses in form fields
     * - Value changes after Enter press
     *
     * @param browser LockedBrowser instance (thread-safe wrapper)
     */
    suspend fun installFormSubmissionMonitor(browser: LockedBrowser) {
        browser.mainFrame().ifPresent { frame ->
            frame.executeJavaScript<Unit>("""
                (function() {
                    if (window.__BOSS_FORM_MONITOR_INSTALLED) {
                        console.log('[BOSS] Form monitor already installed, skipping');
                        return;
                    }
                    window.__BOSS_FORM_MONITOR_INSTALLED = true;

                    console.log('[BOSS] 📊 Form submission monitor installed');

                    // Monitor all form submissions
                    document.addEventListener('submit', function(e) {
                        console.log('[BOSS] ===== FORM SUBMIT DETECTED =====');
                        console.log('[BOSS] Form action:', e.target.action || 'N/A');
                        console.log('[BOSS] Form method:', e.target.method || 'N/A');

                        // Log all BOSS-filled fields
                        const filledFields = document.querySelectorAll('[data-boss-filled="true"]');
                        console.log('[BOSS] BOSS-filled fields count:', filledFields.length);

                        filledFields.forEach((field, index) => {
                            console.log(`[BOSS] Field ${'$'}{index + 1}:`, {
                                name: field.name || field.id || 'unnamed',
                                type: field.type || 'N/A',
                                value: field.value || 'EMPTY',
                                valueLength: field.value.length,
                                filledAt: field.getAttribute('data-boss-filled-at')
                            });
                        });

                        console.log('[BOSS] ===== FORM SUBMIT END =====');
                    }, true);

                    // Monitor Enter key presses in form fields
                    document.addEventListener('keydown', function(e) {
                        if (e.key === 'Enter' && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) {
                            console.log('[BOSS] ===== ENTER KEY IN FORM FIELD =====');
                            console.log('[BOSS] Field:', e.target.name || e.target.id || 'unnamed');
                            console.log('[BOSS] Field type:', e.target.type || 'N/A');
                            console.log('[BOSS] Current value:', e.target.value || 'EMPTY');
                            console.log('[BOSS] Value length:', e.target.value.length);
                            console.log('[BOSS] Is BOSS-filled:', e.target.getAttribute('data-boss-filled') || 'NO');
                            console.log('[BOSS] Form:', e.target.form ? (e.target.form.action || 'has form') : 'NO FORM');

                            // Store reference to field for delayed checks
                            const field = e.target;

                            // Check value again after delays to see if it gets cleared
                            setTimeout(() => {
                                console.log('[BOSS] Value after 100ms:', field.value || 'EMPTY', `(length: ${'$'}{field.value.length})`);
                            }, 100);

                            setTimeout(() => {
                                console.log('[BOSS] Value after 500ms:', field.value || 'EMPTY', `(length: ${'$'}{field.value.length})`);
                            }, 500);

                            setTimeout(() => {
                                console.log('[BOSS] Value after 1000ms:', field.value || 'EMPTY', `(length: ${'$'}{field.value.length})`);
                                console.log('[BOSS] ===== ENTER KEY END =====');
                            }, 1000);
                        }
                    }, true);

                    // Monitor button clicks in forms (critical for DocuSign flow)
                    document.addEventListener('click', function(e) {
                        // Check if clicked element is a button or submit input
                        const isButton = e.target.tagName === 'BUTTON' ||
                                       (e.target.tagName === 'INPUT' && (e.target.type === 'submit' || e.target.type === 'button'));

                        // Also check if clicked on element inside a button
                        const buttonParent = e.target.closest('button, input[type="submit"], input[type="button"]');

                        if (isButton || buttonParent) {
                            const button = buttonParent || e.target;
                            console.log('[BOSS] ===== BUTTON CLICK DETECTED =====');
                            console.log('[BOSS] Button tag:', button.tagName);
                            console.log('[BOSS] Button type:', button.type || 'N/A');
                            console.log('[BOSS] Button text:', button.textContent?.trim() || button.value || 'N/A');
                            console.log('[BOSS] Button name:', button.name || button.id || 'unnamed');

                            // Get all BOSS-filled fields
                            const bossFilled = document.querySelectorAll('[data-boss-filled="true"]');
                            console.log('[BOSS] BOSS-filled fields count:', bossFilled.length);

                            // Log each field's state BEFORE button action
                            bossFilled.forEach((field, index) => {
                                console.log(`[BOSS] Field ${'$'}{index + 1} BEFORE button click:`, {
                                    name: field.name || field.id || 'unnamed',
                                    type: field.type || 'N/A',
                                    fieldType: field.getAttribute('data-boss-field-type'),
                                    value: field.type === 'password' ? '***' : (field.value || 'EMPTY'),
                                    valueLength: field.value.length,
                                    filledAt: field.getAttribute('data-boss-filled-at')
                                });
                            });

                            // Check field values after delays to detect when clearing happens
                            setTimeout(() => {
                                console.log('[BOSS] ----- After 100ms -----');
                                bossFilled.forEach((field, index) => {
                                    console.log(`[BOSS] Field ${'$'}{index + 1}:`, field.name || field.id,
                                               'Value:', field.type === 'password' ? '***' : (field.value || 'EMPTY'),
                                               `(length: ${'$'}{field.value.length})`);
                                });
                            }, 100);

                            setTimeout(() => {
                                console.log('[BOSS] ----- After 500ms -----');
                                bossFilled.forEach((field, index) => {
                                    console.log(`[BOSS] Field ${'$'}{index + 1}:`, field.name || field.id,
                                               'Value:', field.type === 'password' ? '***' : (field.value || 'EMPTY'),
                                               `(length: ${'$'}{field.value.length})`);
                                });
                            }, 500);

                            setTimeout(() => {
                                console.log('[BOSS] ----- After 1000ms -----');
                                bossFilled.forEach((field, index) => {
                                    console.log(`[BOSS] Field ${'$'}{index + 1}:`, field.name || field.id,
                                               'Value:', field.type === 'password' ? '***' : (field.value || 'EMPTY'),
                                               `(length: ${'$'}{field.value.length})`);
                                });
                                console.log('[BOSS] ===== BUTTON CLICK END =====');
                            }, 1000);
                        }
                    }, true);

                    // Monitor blur events on BOSS-filled fields
                    document.addEventListener('blur', function(e) {
                        if ((e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') &&
                            e.target.getAttribute('data-boss-filled') === 'true') {
                            console.log('[BOSS] 🔵 BLUR on BOSS-filled field:', e.target.name || e.target.id || 'unnamed');
                            console.log('[BOSS] Value on blur:', e.target.value || 'EMPTY');
                        }
                    }, true);

                    // Monitor focus events to track field interactions
                    document.addEventListener('focus', function(e) {
                        if ((e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') &&
                            e.target.getAttribute('data-boss-filled') === 'true') {
                            console.log('[BOSS] 🟢 FOCUS on BOSS-filled field:', e.target.name || e.target.id || 'unnamed');
                            console.log('[BOSS] Value on focus:', e.target.value || 'EMPTY');
                        }
                    }, true);

                    console.log('[BOSS] 📊 Form monitor ready - will log form submissions, button clicks, and Enter key events');
                })();
            """.trimIndent())
        }
    }
}
