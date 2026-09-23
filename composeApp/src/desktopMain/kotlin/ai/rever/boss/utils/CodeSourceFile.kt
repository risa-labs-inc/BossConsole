package ai.rever.boss.utils

import java.io.File
import java.net.URL
import java.nio.file.Paths

/** Preserve a file URL's authority when resolving an installed JAR from a network share. */
internal fun codeSourceFile(location: URL): File = Paths.get(location.toURI()).toFile()
