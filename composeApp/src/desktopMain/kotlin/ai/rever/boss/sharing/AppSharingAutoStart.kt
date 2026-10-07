package ai.rever.boss.sharing

/** One automatic attempt per sign-in, with an immediate fence for Stop and account changes. */
internal class AppSharingAutoStart {
    data class Plan(
        val owner: String,
        val windows: List<String>,
        val epoch: Long,
    )

    private var owner: String? = null
    private var epoch = 0L
    private var paused = false
    private var claimed = false

    @Synchronized
    fun accountChanged(value: String?) {
        owner = value
        resume()
    }

    @Synchronized
    fun pause() {
        epoch++
        paused = true
    }

    @Synchronized
    fun resume() {
        epoch++
        paused = false
        claimed = false
    }

    @Synchronized
    fun windowClosed() {
        if (!paused) resume()
    }

    @Synchronized
    fun claim(
        value: String?,
        windows: List<String>,
        preferences: AppSharingLocalPreferences,
        alreadySharing: Boolean,
    ): Plan? {
        val enabled = preferences.automaticSharingEnabled && preferences.relayEnabled
        val eligible = value != null && value == owner && enabled
        val blocked = paused || claimed || alreadySharing || windows.isEmpty()
        if (!eligible || blocked) return null
        claimed = true
        return Plan(requireNotNull(value), windows.take(16), epoch)
    }

    @Synchronized
    fun isCurrent(plan: Plan): Boolean = !paused && owner == plan.owner && epoch == plan.epoch
}
