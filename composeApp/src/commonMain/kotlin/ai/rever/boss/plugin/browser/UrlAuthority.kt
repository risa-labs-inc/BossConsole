package ai.rever.boss.plugin.browser

/*
 * Reading the authority back out of a canonicalUrlKey, and the one rule that depends on it.
 *
 * canonicalUrlKey normalizes a URL into a key; these split that key the way it was built, so
 * a caller never has to guess where the authority ends. Shared across source sets because the
 * URL field's inline completion (commonMain) and the suggestion matcher (desktopMain) have to
 * agree - a host the ghost refuses and the list beside it offers is half a fix.
 */

/**
 * The authority of a [canonicalUrlKey]-shaped address: everything before the path or query.
 *
 * Its own file rather than another declaration in `NavigationOutcomeTracker.kt`, which is at
 * its function budget and is named for something else entirely. Three places need this split
 * and two of them had spelled it out inline in different source sets - the URL field's
 * completion rules and the suggestion matcher's userinfo gate - which is the same shape as
 * the two definitions of "extends" that an earlier review round removed.
 */
fun canonicalAuthority(canonical: String): String = canonical.substringBefore('/').substringBefore('?')

/**
 * Whether a [canonicalUrlKey]-shaped address carries a `user@` before its authority ends.
 *
 * `java.net.URL` reads the host of `https://github.com@evil.example/` as `evil.example`,
 * while [canonicalUrlKey] keeps the `github.com@` - so such an entry passes a
 * [suggestableHost] gate AND matches a typed "git" at index 0. Both the field's inline
 * completion and the suggestion list have to refuse it, and they have to refuse it by the
 * same rule, so the rule lives here rather than once per caller. Chrome strips userinfo out
 * of the omnibox for the same reason.
 */
fun hasUserinfo(canonical: String): Boolean = canonicalAuthority(canonical).contains('@')
