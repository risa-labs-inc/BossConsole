package ai.rever.boss.window

/** Read-only callback-thread gate: native URL editing belongs to its exact browser identity. */
internal fun nativeAddressOwnsBrowserKeys(identity: String?): Boolean =
    identity != null &&
        MacSidebarToolbarBridge.owners.values.any {
            val editing = it.addressField.editing
            !editing.closed && editing.active && editing.input?.identity == identity
        }

/** Finish native editing before the clicked browser handles input; never touch an unrelated window. */
internal fun releaseNativeAddressForPage(identity: String): Boolean {
    if (!nativeAddressOwnsBrowserKeys(identity)) return false
    return scopedNativeToolbarCall(Long.MAX_VALUE) {
        MacSidebarToolbarBridge.owners.values.any {
            val editing = it.addressField.editing
            editing.input?.identity == identity && editing.releaseForPage()
        }
    }
}
