package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.pathutils.DownloadsDirectory

/**
 * Desktop implementation for getting the default downloads directory.
 *
 * The platform rules and their tests live in [DownloadsDirectory], which is what the
 * in-process and out-of-process plugin providers call too. The three used to answer this
 * question differently, so a plugin could be told a folder other than the one the browser
 * saves into - and a different one again depending on which process it was loaded in.
 */
actual fun getDefaultDownloadsDirectory(): String = DownloadsDirectory.current()
