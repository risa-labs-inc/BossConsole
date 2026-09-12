package ai.rever.boss.kernel

/** One status slot must retain every failure, even when other services start successfully. */
internal fun serviceStartupSummary(
    spawned: Int,
    missing: List<String>,
    failed: List<String>,
): String =
    buildList {
        if (missing.isNotEmpty()) {
            add("Missing JARs: ${missing.joinToString(", ")}. Check the installation and service logs.")
        }
        if (failed.isNotEmpty()) add("Failed to spawn: ${failed.joinToString(", ")}. Check service logs.")
        add("Microkernel: $spawned service(s) spawned (readiness not verified).")
    }.joinToString(" ")

/**
 * Whether the startup summary must reach the user at all. A fully successful cohort spawn is
 * not a user-visible event - reporting it toasted every healthy KERNEL launch with
 * developer-worded text for 12 seconds (BossConsole#450's review) - while a missing JAR or a
 * failed spawn is exactly the information an operator needs at startup. The kernel log keeps
 * the full summary either way; only the toast is gated.
 */
internal fun serviceStartupSummaryNeedsNotice(
    missing: List<String>,
    failed: List<String>,
): Boolean = missing.isNotEmpty() || failed.isNotEmpty()
