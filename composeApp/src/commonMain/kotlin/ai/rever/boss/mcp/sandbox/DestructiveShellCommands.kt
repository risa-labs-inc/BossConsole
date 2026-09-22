package ai.rever.boss.mcp.sandbox

/**
 * Whether a shell command line reads as destructive, for [DefaultMcpRiskEvaluator].
 *
 * **This is a wording heuristic, not a shell parser, and its errors only go one way.** Both HIGH and
 * CRITICAL require approval; CRITICAL is the tier a standing "Always Allow" on a shell tool does not
 * cover, so a command rated HIGH that destroys data is one nobody was asked about. A false positive
 * costs one more prompt. A false negative is an unreviewed `rm -rf ~`.
 *
 * It used to be eight literal substrings (`rm -rf`, `git push -f`, ...), which a differently spelled
 * command sails past: `rm -fr`, `rm -r -f`, `rm --recursive --force`, `\rm -rf`, `r''m -rf`,
 * `git reset --hard`, `git push origin +main`, `Remove-Item -Recurse`, `curl ... | sh`. So the command
 * line is now split into its simple commands, the command word of each is found past `sudo`/`env`/
 * `xargs` and friends, and its flags are read as flags. Every legacy wording still matches.
 *
 * [command] is expected trimmed and lower-cased, which is how the evaluator calls it - so `-R` and
 * `-r`, `-D` and `-d` are the same here, and a rule that depends on the difference cannot exist.
 */
internal object DestructiveShellCommands {
    fun matches(command: String): Boolean {
        if (command.isEmpty()) return false
        return legacyWording(command) ||
            isForkBomb(command) ||
            REMOTE_EXECUTION.any { it.containsMatchIn(command) } ||
            DEVICE_REDIRECT.containsMatchIn(command) ||
            simpleCommands(command).any(::isDestructive)
    }

    private fun legacyWording(command: String): Boolean =
        command.contains("rm -rf") ||
            command.contains("del /s") ||
            command.contains("format ") ||
            command.contains("mkfs") ||
            command.contains("git push --force") ||
            command.contains("git push -f") ||
            command.contains("dd if=") ||
            command.contains("chmod -r 777")

    private fun isForkBomb(command: String): Boolean = command.replace(" ", "").contains(":(){")

    /**
     * The command line as a list of simple commands, each a list of words.
     *
     * Quotes, backslashes and carets are dropped first, because they are how `\rm`, `r''m` and
     * `"rm"` still run `rm`; the separators are `;`, `&`, `|`, newlines, parentheses, braces and the
     * backtick, which covers `a && b`, pipelines and `$(...)`.
     */
    private fun simpleCommands(command: String): List<List<String>> =
        command
            .filterNot { it in QUOTING }
            .split(SEPARATORS)
            .map { segment -> segment.split(WHITESPACE).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }

    private fun isDestructive(words: List<String>): Boolean {
        val start = commandWordIndex(words)
        if (start >= words.size) return false
        return isDestructiveCommand(baseName(words[start]), words.drop(start + 1))
    }

    /** Past `sudo`, `env FOO=1`, `nice -n 10`, `xargs -0`, `timeout 5` and a leading `FOO=1`. */
    private fun commandWordIndex(words: List<String>): Int {
        var index = 0
        var sawWrapper = false
        while (index < words.size) {
            val word = words[index]
            val isWrapper = word in WRAPPERS
            val skippable =
                isWrapper ||
                    isAssignment(word) ||
                    (sawWrapper && (word.startsWith("-") || word.all { it.isDigit() }))
            if (!skippable) break
            if (isWrapper) sawWrapper = true
            index++
        }
        return index
    }

    private val QUOTING = setOf('\'', '"', '\\', '^')
    private val SEPARATORS = Regex("[;&|\\n\\r(){}`]+")
    private val WHITESPACE = Regex("\\s+")
    private val DEVICE_REDIRECT = Regex(">\\s*/dev/(sd|nvme|hd|vd|xvd|disk|mmcblk)")

    private const val INTERPRETERS =
        "sh|bash|zsh|dash|ksh|fish|python3?|perl|ruby|node|pwsh|powershell|iex|invoke-expression"
    private const val DOWNLOADERS = "curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod"

    /** A download piped into an interpreter, run from a substitution, or handed to `iex`. */
    private val REMOTE_EXECUTION =
        listOf(
            Regex("\\b($DOWNLOADERS)\\b[^|;&\\n]*\\|\\s*(sudo\\s+)?($INTERPRETERS)\\b"),
            Regex("\\b(sh|bash|zsh|dash)\\s+(-c\\s+)?[\"']?(<\\(|\\$\\()\\s*(curl|wget)\\b"),
            Regex("\\b(iex|invoke-expression)\\b.*(downloadstring|downloadfile|$DOWNLOADERS)"),
        )
}

private val WRAPPERS =
    setOf(
        "sudo",
        "doas",
        "env",
        "nice",
        "nohup",
        "time",
        "command",
        "builtin",
        "exec",
        "xargs",
        "timeout",
        "stdbuf",
        "ionice",
        "setsid",
        "then",
        "do",
        "else",
    )
private val DISK_TOOLS =
    setOf(
        "mke2fs",
        "wipefs",
        "shred",
        "fdisk",
        "sfdisk",
        "parted",
        "gdisk",
        "sgdisk",
        "blkdiscard",
        "diskpart",
        "format",
    )
private val PERMISSION_TOOLS = setOf("chmod", "chown", "chgrp")
private val WIDE_MODES = setOf("777", "666", "000", "a+rwx", "ugo+rwx", "a=rwx")
private val ROOT_TARGETS = setOf("/", "/*", "~", "*", "..")
private val WINDOWS_REMOVERS = setOf("del", "erase", "rd", "rmdir", "remove-item", "ri")
private val WINDOWS_REMOVE_FLAGS = setOf("/s", "/f", "/q")

private fun isAssignment(word: String): Boolean {
    val equals = word.indexOf('=')
    return equals > 0 && (word[0].isLetter() || word[0] == '_') && '/' !in word.substring(0, equals)
}

private fun baseName(word: String): String =
    word
        .substringAfterLast('/')
        .removeSuffix(".exe")
        .removeSuffix(".cmd")
        .removeSuffix(".bat")
        .removeSuffix(".ps1")

@Suppress("CyclomaticComplexMethod") // One arm per command family; splitting it would hide the list.
private fun isDestructiveCommand(
    name: String,
    args: List<String>,
): Boolean =
    when {
        name == "rm" -> isRecursive(args) || "--no-preserve-root" in args
        name == "find" -> isDestructiveFind(args)
        name == "git" -> GitWording.isDestructive(args)
        name == "dd" -> args.any { it.startsWith("of=") }
        name.startsWith("mkfs") || name in DISK_TOOLS -> true
        name == "cipher" -> args.any { it.startsWith("/w") }
        name in PERMISSION_TOOLS -> isRecursive(args) && args.any { it in WIDE_MODES || it in ROOT_TARGETS }
        name in WINDOWS_REMOVERS -> args.any { it in WINDOWS_REMOVE_FLAGS || it.startsWith("-rec") }
        else -> false
    }

/** `-r`, `-fr`, `-rf`, `-rfv` and `--recursive`; the single-dash test is on the cluster, not a word. */
private fun isRecursive(args: List<String>): Boolean = args.any { it == "--recursive" || isShortFlagCluster(it, 'r') }

private fun isShortFlagCluster(
    word: String,
    flag: Char,
): Boolean = word.startsWith("-") && !word.startsWith("--") && flag in word.drop(1)

private fun isDestructiveFind(args: List<String>): Boolean =
    "-delete" in args ||
        (args.any { it == "-exec" || it == "-execdir" } && args.any { baseName(it) == "rm" || baseName(it) == "shred" })

/** The `git` subcommands that lose history or work: forced or deleting pushes, hard resets, forced cleans. */
private object GitWording {
    private val OPTIONS_WITH_VALUE = setOf("-c", "-C", "--git-dir", "--work-tree", "--namespace", "--exec-path")

    fun isDestructive(args: List<String>): Boolean {
        val words = withoutGlobalOptions(args)
        val subcommand = words.firstOrNull() ?: return false
        val rest = words.drop(1)
        return when (subcommand) {
            "push" -> rest.any(::isForcefulPushWord)
            "reset" -> "--hard" in rest
            "clean" -> rest.any { it == "--force" || isShortFlagCluster(it, 'f') }
            "filter-branch", "filter-repo" -> true
            else -> false
        }
    }

    private fun withoutGlobalOptions(args: List<String>): List<String> {
        var index = 0
        while (index < args.size && args[index].startsWith("-")) {
            index += if (args[index] in OPTIONS_WITH_VALUE) 2 else 1
        }
        return args.drop(index)
    }

    private fun isForcefulPushWord(word: String): Boolean =
        word.startsWith("--force") ||
            word == "--mirror" ||
            word == "--delete" ||
            word == "--prune" ||
            isShortFlagCluster(word, 'f') ||
            word.startsWith("+") ||
            (word.startsWith(":") && word.length > 1)
}
