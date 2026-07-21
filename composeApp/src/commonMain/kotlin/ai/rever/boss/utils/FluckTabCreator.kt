package ai.rever.boss.utils

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabTypeId
import kotlin.random.Random

/**
 * Utility for creating Fluck tabs programmatically
 */
object FluckTabCreator {
    
    /**
     * Create a FluckTabInfo for opening a URL
     */
    fun createFluckTab(url: String, title: String = "Loading..."): FluckTabInfo {
        return FluckTabInfo(
            id = "webauthn-${Random.nextLong()}",
            typeId = TabTypeId("fluck"),
            _title = title,
            url = url
        )
    }
    
    /**
     * Create a FluckTabInfo specifically for WebAuthn registration
     */
    fun createWebAuthnTab(url: String): FluckTabInfo {
        return createFluckTab(url, "WebAuthn Registration")
    }
}
