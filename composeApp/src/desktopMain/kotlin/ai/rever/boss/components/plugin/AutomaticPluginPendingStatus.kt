package ai.rever.boss.components.plugin

/** Pending work can wait for views, an installer lease, retry backoff or manual intervention. */
internal fun automaticPluginPendingStatus(
    waiting: Int,
    retrying: Int,
    paused: Int,
): String =
    buildList {
        if (waiting > 0) add("$waiting plugin update(s) waiting for views to close or an installer to finish")
        if (retrying > 0) add("$retrying plugin update(s) waiting to retry")
        if (paused > 0) {
            add("$paused plugin update(s) paused after three failures; " + "update manually or wait for a new release")
        }
    }.joinToString(". ")
