package ai.rever.boss.utils

/**
 * Path utility functions for cross-platform file path handling.
 *
 * This file delegates to the canonical implementation in plugin-path-utils module.
 * See plugin-path-utils/PathUtils.kt for full documentation and known limitations.
 */

import ai.rever.boss.plugin.pathutils.extractFileName as coreExtractFileName
import ai.rever.boss.plugin.pathutils.extractParentName as coreExtractParentName

/**
 * Extract file or folder name from a path, handling both Unix (/) and Windows (\) separators.
 * @see ai.rever.boss.plugin.pathutils.extractFileName
 */
fun String.extractFileName(): String = coreExtractFileName()

/**
 * Extract parent folder name from a path, handling both Unix (/) and Windows (\) separators.
 * @see ai.rever.boss.plugin.pathutils.extractParentName
 */
fun String.extractParentName(): String = coreExtractParentName()
