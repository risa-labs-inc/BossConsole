package ai.rever.boss.pet

/**
 * The one [BossPetController] the floating pet renders and that producers report to.
 *
 * App-global rather than per-window: the pet is a single floating companion, and a build running an
 * agent in one window should show "working" on the same pet whatever window has focus. Anything with
 * a long-running action - an agent turn, a build, an install - reaches it as
 * `BossPet.controller.taskStarted(id)` / `taskFinished(id, label)` / `taskFailed(id, label)`.
 *
 * A plain object, like [ai.rever.boss.config.SwipeNavSettingsManager] and the other host-wide
 * managers: there is exactly one pet, its controller carries no window-scoped state, and a producer
 * deep in a call stack needs to reach it without an injected handle.
 */
object BossPet {
    val controller: BossPetController = BossPetController()
}
