package ai.rever.boss.mcp.secrets

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A note on the trailing `Unit` in some tests: `runBlocking { ... assertIs<T>(x) }` returns `T`,
 * which makes the test method non-void, and JUnit silently skips a non-void test method. The
 * compiled class is what runs, so the shape of the last expression matters here.
 */
class SecretReferenceResolverTest {
    private val a = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val b = "00000000-0000-4000-8000-000000000001"

    private fun passwordOf(id: String) = SecretReference(id, SecretField.PASSWORD)

    private fun record(
        id: String,
        password: String = "pw-$id",
        notes: String? = null,
        tags: List<String> = emptyList(),
    ) = SecretRecord(
        id = id,
        website = "site-$id",
        username = "user-$id",
        password = password,
        notes = notes,
        tags = tags,
    )

    /** A vault that pages [records] and counts the pages it served. */
    private class PagedVault(
        private val records: List<SecretRecord>,
    ) : SecretLookup {
        var pagesServed = 0

        override suspend fun page(
            limit: Int,
            offset: Int,
        ): Result<List<SecretRecord>> {
            pagesServed += 1
            return Result.success(records.drop(offset).take(limit))
        }
    }

    @Test
    fun `resolves every field of a found secret and describes it without the value`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a, password = "s3cret!!", notes = "note")))
            val refs = SecretField.entries.map { SecretReference(a, it) }.toSet()
            val resolution = SecretReferenceResolver(vault).resolve(refs)
            assertIs<SecretResolution.Resolved>(resolution)
            assertEquals("s3cret!!", resolution.values[SecretReference(a, SecretField.PASSWORD)])
            assertEquals("user-$a", resolution.values[SecretReference(a, SecretField.USERNAME)])
            assertEquals("note", resolution.values[SecretReference(a, SecretField.NOTES)])
            assertEquals(3, resolution.descriptors.size)
            resolution.descriptors.forEach {
                assertEquals("site-$a", it.website)
                assertFalse(it.display.contains("s3cret!!"))
            }
        }

    @Test
    fun `plaintext-bearing resolver objects redact their string form`() =
        runBlocking {
            val plaintext = "never-print-this"
            val source = record(a, password = plaintext, notes = plaintext)
            val resolution = SecretReferenceResolver(PagedVault(listOf(source))).resolve(setOf(passwordOf(a)))
            assertFalse(source.toString().contains(plaintext), source.toString())
            assertFalse(resolution.toString().contains(plaintext), resolution.toString())
        }

    @Test
    fun `walks pages until every reference is found and no further`() =
        runBlocking {
            val filler = (1..450).map { record("11111111-1111-4111-8111-%012d".format(it)) }
            val vault = PagedVault(filler + record(a) + record(b))
            val resolution = SecretReferenceResolver(vault, pageSize = 200).resolve(setOf(passwordOf(a)))
            assertIs<SecretResolution.Resolved>(resolution)
            assertEquals(3, vault.pagesServed)
        }

    @Test
    fun `stops at the end of the vault and reports the missing id`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a)))
            val resolution =
                SecretReferenceResolver(vault).resolve(
                    setOf(SecretReference(a, SecretField.PASSWORD), SecretReference(b, SecretField.PASSWORD)),
                )
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains(b))
            assertFalse(resolution.reason.contains("pw-"))
            assertEquals(1, vault.pagesServed)
        }

    @Test
    fun `an ai provider key is forbidden even when everything else resolves`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a, tags = listOf(SecretReferenceResolver.AI_PROVIDER_TAG)), record(b)))
            val resolution =
                SecretReferenceResolver(vault).resolve(
                    setOf(SecretReference(a, SecretField.PASSWORD), SecretReference(b, SecretField.PASSWORD)),
                )
            assertIs<SecretResolution.Forbidden>(resolution)
            assertTrue(resolution.reason.contains(a))
        }

    @Test
    fun `forbidden outranks missing`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a, tags = listOf("ai-provider"))))
            val resolution =
                SecretReferenceResolver(vault).resolve(
                    setOf(SecretReference(a, SecretField.PASSWORD), SecretReference(b, SecretField.PASSWORD)),
                )
            assertIs<SecretResolution.Forbidden>(resolution)
            Unit
        }

    @Test
    fun `a secret without notes cannot supply its notes`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a, notes = null)))
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.NOTES)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains("notes"))
        }

    @Test
    fun `a vault read failure is unresolved and names the failure type only`() =
        runBlocking {
            val vault = SecretLookup { _, _ -> Result.failure(IllegalStateException("token pw-secret leaked?")) }
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains("IllegalStateException"))
            assertFalse(resolution.reason.contains("leaked"))
        }

    @Test
    fun `a vault that throws is unresolved, not a crash`() =
        runBlocking {
            val vault = SecretLookup { _, _ -> error("down") }
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Unresolved>(resolution)
            Unit
        }

    @Test
    fun `the page walk is bounded`() =
        runBlocking {
            val endless =
                SecretLookup { limit, _ ->
                    Result.success(List(limit) { record("22222222-2222-4222-8222-%012d".format(it)) })
                }
            val resolution =
                SecretReferenceResolver(endless, pageSize = 10, maxPages = 3).resolve(setOf(passwordOf(a)))
            assertIs<SecretResolution.Unresolved>(resolution)
            Unit
        }

    @Test
    fun `ids are matched case-insensitively against the vault`() =
        runBlocking {
            val vault = PagedVault(listOf(record(a.uppercase(), password = "s3cret!!")))
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Resolved>(resolution)
            Unit
        }

    @Test
    fun `no references resolves to nothing without touching the vault`() =
        runBlocking {
            val vault = PagedVault(emptyList())
            assertIs<SecretResolution.Resolved>(SecretReferenceResolver(vault).resolve(emptySet()))
            assertEquals(0, vault.pagesServed)
        }
}
