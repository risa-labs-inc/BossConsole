package ai.rever.boss.updater

import java.io.File
import java.util.concurrent.TimeUnit

/** Bound on the PlistBuddy read below; a hung helper must not wedge the install path. */
private const val PLIST_READ_TIMEOUT_SECONDS = 5L

// Refusing an update this Mac cannot launch.
//
// Engine upgrades move the app's minimum macOS (JxBrowser 9.4.0 / Chromium 151
// took it 12.0 -> 13.0) and the release manifest carries no minimum-OS field, so
// nothing upstream stops an unsupported update being offered. The installer path
// `rm -rf`s the installed BOSS.app before copying the new one, so without a check
// a user on an older macOS loses a working install and gets one Launch Services
// refuses to open, with no way back.
//
// Kept out of UpdateInstaller because none of it touches that object's state and
// it is the part most worth testing in isolation.

/**
 * A message explaining why [appBundle] cannot run here, or null if it can.
 *
 * The requirement is read from the incoming bundle's `LSMinimumSystemVersion`
 * rather than a constant, so it tracks whatever DMG is being installed and needs no
 * maintenance when the floor moves again.
 */
internal fun unsupportedOsError(appBundle: File): String? {
    val isMac =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    return osFloorMessage(
        required = if (isMac) readMinimumSystemVersion(appBundle) else null,
        current = System.getProperty("os.version"),
    )
}

/**
 * The decision itself, separated from where the two versions come from.
 *
 * Extracted because this — not the comparator — is what sits in front of the
 * irreversible delete, and the argument order is the part that silently inverts:
 * swapping [current] and [required] blocks every supported Mac while sending every
 * unsupported one down the destructive path, and a comparator-only test suite stays
 * green through it. Pure, so it runs on every CI leg rather than only the macOS one.
 *
 * Blank is treated as unknown, not as "version zero" — a blank [current] would
 * otherwise sort below any floor and refuse every update.
 */
internal fun osFloorMessage(
    required: String?,
    current: String?,
): String? =
    if (!required.isNullOrBlank() &&
        !current.isNullOrBlank() &&
        compareVersions(current, required) < 0
    ) {
        "This update requires macOS $required or later — this Mac runs macOS $current. " +
            "Your current version of BOSS has been kept."
    } else {
        null
    }

/**
 * Read `LSMinimumSystemVersion` from a bundle, or null when it can't be determined.
 *
 * Fails **open** on anything unreadable — a missing key, a PlistBuddy that won't
 * run, a timeout. Blocking every update because a plist could not be parsed would
 * be a worse failure than the one being prevented, and the installer script repeats
 * this check before the destructive step. Note PlistBuddy reports a missing key on
 * *stdout* with a non-zero exit, so the exit status is what has to be trusted.
 */
internal fun readMinimumSystemVersion(appBundle: File): String? =
    runCatching {
        val plist = File(appBundle, "Contents/Info.plist")
        if (!plist.exists()) return@runCatching null
        val process =
            ProcessBuilder(
                "/usr/libexec/PlistBuddy",
                "-c",
                "Print :LSMinimumSystemVersion",
                plist.absolutePath,
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        // waitFor BEFORE readText. readText blocks until stdout hits EOF, so
        // reading first means a PlistBuddy that hangs with the pipe open never
        // reaches the timeout at all — the bound would be dead code and the
        // install path would wedge indefinitely, which is the exact failure this
        // timeout exists to prevent. The output is a few bytes and cannot fill the
        // pipe buffer, so nothing is lost by waiting first.
        if (!process.waitFor(PLIST_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val value =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        // Validate the shape, mirroring the shell guard. Both halves read the same
        // DMG-controlled file; only one validating it left the other interpolating
        // whatever PlistBuddy printed straight into a user-facing string.
        if (process.exitValue() == 0 && value.matches(DOTTED_VERSION)) value else null
    }.getOrNull()

/** `13`, `13.0` or `13.0.1` — the only shapes LSMinimumSystemVersion legitimately takes. */
private val DOTTED_VERSION = Regex("""^\d+(\.\d+){0,2}$""")

/** Numeric dotted-version compare; a non-numeric component sorts as 0. */
internal fun compareVersions(
    left: String,
    right: String,
): Int {
    val l = left.split('.').map { it.toIntOrNull() ?: 0 }
    val r = right.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(l.size, r.size)) {
        val diff = (l.getOrElse(i) { 0 }).compareTo(r.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
