package ai.rever.boss.services.supabase

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every RPC the Kotlin client calls by name must be a function that some migration creates.
 *
 * PostgREST reports an unknown function when the call runs, not when the app builds, so a name
 * that was never shipped stays invisible until something calls it. `RoleService.userHasRole`
 * called `check_user_has_role`, which no migration has ever created, and nothing noticed because
 * nothing called it.
 *
 * What this does not check, so nobody assumes more: names built at runtime, such as the
 * plugin-facing proxy in `SupabaseDataProviderImpl`, because only the literal forms
 * `function = "..."` and `rpc("...")` are read; a function a later migration drops, because only
 * CREATE FUNCTION is read; and parameter names, which PostgREST matches on as well.
 */
class RpcNamesExistTest {
    private val root = repoRoot()

    private val created: Set<String> =
        File(root, "supabase/migrations")
            .listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .flatMap { file -> CREATE_FUNCTION.findAll(file.readText()).map { it.groupValues[1].lowercase() } }
            .toSet()

    /** RPC name to the `File.kt:line` places that call it. */
    private val called: Map<String, List<String>> =
        kotlinSourcesUnder(root, "composeApp/src", "plugin-platform", "modules")
            .filter { MAIN_SOURCE.containsMatchIn(it.relativeTo(root).invariantSeparatorsPath) }
            .flatMap { file -> rpcCallsIn(file) }
            .groupBy({ it.first }, { it.second })

    private fun rpcCallsIn(file: File): List<Pair<String, String>> {
        val text = file.readText()
        if ("rpc(" !in text) return emptyList()
        return RPC_NAME
            .findAll(text)
            .map { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                match.groupValues[1] to "${file.name}:$line"
            }.toList()
    }

    @Test
    fun `every RPC the client calls by name is created by a migration`() {
        assertEquals(emptyMap(), called.filterKeys { it !in created }, "RPC names that no migration creates")
    }

    @Test
    fun `the scan finds the calls it exists to check`() {
        // A pattern that matched nothing would pass the test above forever.
        assertTrue("get_user_roles_with_names" in called, "calls found: ${called.keys}")
        assertTrue("get_user_roles_with_names" in created, "functions found: ${created.size}")
    }

    private companion object {
        val CREATE_FUNCTION =
            Regex(
                """create\s+(?:or\s+replace\s+)?function\s+(?:"?public"?\.)?"?([a-z_][a-z0-9_]*)"?\s*\(""",
                RegexOption.IGNORE_CASE,
            )
        val RPC_NAME = Regex("""(?:function\s*=\s*|\brpc\(\s*)"([a-z_][a-z0-9_]*)"""")
        val MAIN_SOURCE = Regex("""/src/(?:main|[A-Za-z]+Main)/""")
    }
}
