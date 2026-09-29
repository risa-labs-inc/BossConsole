package ai.rever.boss.services.passkey

import java.io.File
import java.nio.file.Files

/**
 * Names a script file that an executor is about to hand to `swift` or
 * `powershell -File`, and proves it stays inside the script directory.
 *
 * `File(dir, name)` / `Paths.get(dir, name)` happily resolve `../outside.swift`
 * or an absolute `name` to somewhere the directory never contained, and the
 * executors then run whatever they found. The two checks are deliberately
 * separate: [requireSimpleName] is pure and runs before any filesystem or
 * process work so a hostile name is refused deterministically, and
 * [resolveInside]'s canonical containment is the backstop for names that pass
 * the charset but resolve outside anyway (a symlink inside the script
 * directory pointing out).
 */
internal object ScriptFileGuard {
    // Letters, digits, dot, underscore, dash. No separator chars at all, so a
    // name can never address a different directory.
    private val safeNamePattern = Regex("[A-Za-z0-9._-]+")

    /**
     * Rejects any name that is not a plain file name: separators, traversal
     * segments, or anything outside [safeNamePattern]. Pure - throws before
     * the caller resolves directories or starts a process.
     */
    fun requireSimpleName(name: String) {
        require(safeNamePattern.matches(name) && !name.contains("..")) {
            "Refusing script name with disallowed characters or traversal: $name"
        }
    }

    /**
     * Resolves [name] inside [dir] and asserts canonical containment. Callers
     * should run [requireSimpleName] first; this catches what a name check
     * cannot (e.g. the resolved file being a symlink pointing outside [dir]).
     */
    fun resolveInside(
        dir: File,
        name: String,
    ): File {
        // toRealPath resolves symlinks on every platform; canonicalFile does
        // not on Windows, which would let a symlink inside dir slip through.
        val dirReal = dir.toPath().toRealPath()
        val candidate = dirReal.resolve(name)
        val resolved = if (Files.exists(candidate)) candidate.toRealPath() else candidate.normalize()
        require(resolved.startsWith(dirReal)) {
            "Refusing script outside its directory: $name"
        }
        return resolved.toFile()
    }
}
