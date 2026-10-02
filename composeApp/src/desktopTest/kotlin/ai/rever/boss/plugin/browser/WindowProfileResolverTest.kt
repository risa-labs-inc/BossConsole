package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine, the profile found on it and the per-profile setup must all come from one engine. A
 * recycle replaces the engine under its own lock; the resolver works under that same lock, so a
 * name already prepared on the OLD engine is prepared again on the new one.
 */
class WindowProfileResolverTest {
    private class Engine(
        val name: String,
    )

    private data class Profile(
        val engine: String,
        val name: String,
    )

    private class FakeOps : WindowBrowserProfiles.EngineOps<Engine, Profile> {
        var current = Engine("E1")
        val prepared = mutableListOf<Profile>()
        private val lock = Any()

        override fun <T> withEngine(block: (Engine) -> T): T = synchronized(lock) { block(current) }

        override fun findOrCreate(
            engine: Engine,
            name: String,
        ) = Profile(engine.name, name)

        override fun prepare(profile: Profile) {
            prepared += profile
        }

        /** What FluckEngine.recycleWedgedEngine does, under the same lock. */
        fun recycle(to: String) = synchronized(lock) { current = Engine(to) }
    }

    @Test
    fun `a profile is prepared once per engine`() {
        val ops = FakeOps()
        val resolver = WindowBrowserProfiles.Resolver(ops)
        resolver.profileNamed("boss-window-work")
        resolver.profileNamed("boss-window-work")
        assertEquals(listOf(Profile("E1", "boss-window-work")), ops.prepared)
    }

    @Test
    fun `after a recycle the same name is prepared again, on the new engine's profile`() {
        val ops = FakeOps()
        val resolver = WindowBrowserProfiles.Resolver(ops)
        resolver.profileNamed("boss-window-work")
        ops.recycle(to = "E2")

        val profile = resolver.profileNamed("boss-window-work")

        assertEquals(Profile("E2", "boss-window-work"), profile, "the profile belongs to the current engine")
        assertEquals(
            listOf(Profile("E1", "boss-window-work"), Profile("E2", "boss-window-work")),
            ops.prepared,
            "E2's copy got its handlers even though the name was prepared on E1",
        )
    }

    @Test
    fun `a failed setup is retried on the next lookup`() {
        val ops =
            object : WindowBrowserProfiles.EngineOps<Engine, Profile> {
                var attempts = 0
                val engine = Engine("E1")

                override fun <T> withEngine(block: (Engine) -> T): T = block(engine)

                override fun findOrCreate(
                    engine: Engine,
                    name: String,
                ) = Profile(engine.name, name)

                override fun prepare(profile: Profile) {
                    attempts++
                    if (attempts == 1) error("transient")
                }
            }
        val resolver = WindowBrowserProfiles.Resolver(ops)
        resolver.profileNamed("p")
        resolver.profileNamed("p")
        resolver.profileNamed("p")
        assertEquals(2, ops.attempts, "retried once after the failure, then prepared for good")
    }
}
