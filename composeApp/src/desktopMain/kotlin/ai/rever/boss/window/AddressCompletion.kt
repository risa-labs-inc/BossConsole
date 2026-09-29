package ai.rever.boss.window

/** Ignore stale plugin snapshots and complete only a caret at the end of the typed prefix. */
internal fun addressCompletion(
    typed: String,
    modelText: String,
    completion: String?,
    start: Int,
    end: Int,
): String? {
    val caretAtEnd = start == typed.length && end == typed.length
    val matchesInput = typed == modelText && typed.isNotEmpty()
    val extendsPrefix = completion != null && completion.length > typed.length && completion.startsWith(typed, true)
    return completion.takeIf { caretAtEnd && matchesInput && extendsPrefix }
}
