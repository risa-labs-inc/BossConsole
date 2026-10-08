package ai.rever.boss.startup

internal class DesktopLaunchArguments(
    val cliArgs: Array<String>,
    val windowlessRequested: Boolean,
)

/** Strip the GUI lifecycle flag before any CLI dispatch or single-instance forwarding. */
internal fun parseDesktopLaunchArguments(args: Array<String>): DesktopLaunchArguments =
    DesktopLaunchArguments(
        cliArgs = args.filterNot { it == "--no-window" }.toTypedArray(),
        windowlessRequested = "--no-window" in args,
    )
