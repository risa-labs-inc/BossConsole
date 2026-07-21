@file:Suppress("UNUSED")
package ai.rever.boss.components.registery

import ai.rever.boss.plugin.api.TabIcon as PluginTabIcon

/**
 * Re-exports from plugin-api module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.api
 */

// Re-export all types from plugin-api
typealias TabTypeId = ai.rever.boss.plugin.api.TabTypeId
typealias TabIcon = PluginTabIcon
typealias TabInfo = ai.rever.boss.plugin.api.TabInfo
typealias TabTypeInfo = ai.rever.boss.plugin.api.TabTypeInfo
typealias TabComponentWithUI = ai.rever.boss.plugin.api.TabComponentWithUI

// Re-export nested types of TabIcon for import compatibility
// e.g., import ai.rever.boss.components.registery.Vector
typealias Vector = PluginTabIcon.Vector
typealias Image = PluginTabIcon.Image
