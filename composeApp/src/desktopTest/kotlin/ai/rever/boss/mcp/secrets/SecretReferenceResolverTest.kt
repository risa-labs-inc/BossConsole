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

    /** A vault that answers by id and remembers every id it was asked for, in order. */
    private class Vault(
        private val records: List<SecretRecord>,
    ) : SecretLookup {
        val asked = mutableListOf<String>()

        override suspend fun byId(id: String): Result<SecretRecord?> {
            asked += id
            return Result.success(records.firstOrNull { it.id.equals(id, ignoreCase = true) })
        }
    }

    @Test
    fun `resolves every field of a found secret and describes it without the value`() =
        runBlocking {
            val vault = Vault(listOf(record(a, password = "s3cret!!", notes = "note")))
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
            val resolution = SecretReferenceResolver(Vault(listOf(source))).resolve(setOf(passwordOf(a)))
            assertFalse(source.toString().contains(plaintext), source.toString())
            assertFalse(resolution.toString().contains(plaintext), resolution.toString())
        }

    @Test
    fun `asks the vault for each referenced id once, whatever else the vault holds`() =
        runBlocking {
            val filler = (1..450).map { record("11111111-1111-4111-8111-%012d".format(it)) }
            val vault = Vault(filler + record(a, notes = "note") + record(b))
            val refs = SecretField.entries.map { SecretReference(a, it) }.toSet() + passwordOf(b)
            val resolution = SecretReferenceResolver(vault).resolve(refs)
            assertIs<SecretResolution.Resolved>(resolution)
            assertEquals(listOf(a, b), vault.asked, "one read per distinct id, three fields of a share one")
        }

    @Test
    fun `an unknown id is reported without reading anything else`() =
        runBlocking {
            val vault = Vault(listOf(record(a)))
            val resolution =
                SecretReferenceResolver(vault).resolve(
                    setOf(SecretReference(a, SecretField.PASSWORD), SecretReference(b, SecretField.PASSWORD)),
                )
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains(b))
            assertFalse(resolution.reason.contains("pw-"))
            assertEquals(listOf(a, b), vault.asked)
        }

    @Test
    fun `every missing id is reported at once, not one per attempt`() =
        runBlocking {
            val c = "00000000-0000-4000-8000-000000000002"
            val vault = Vault(listOf(record(a)))
            val resolution =
                SecretReferenceResolver(vault).resolve(setOf(passwordOf(a), passwordOf(b), passwordOf(c)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains(b) && resolution.reason.contains(c), resolution.reason)
        }

    @Test
    fun `an ai provider key is forbidden even when everything else resolves`() =
        runBlocking {
            val vault = Vault(listOf(record(a, tags = listOf(SecretReferenceResolver.AI_PROVIDER_TAG)), record(b)))
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
            val vault = Vault(listOf(record(a, tags = listOf("ai-provider"))))
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
            val vault = Vault(listOf(record(a, notes = null)))
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.NOTES)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains("notes"))
        }

    @Test
    fun `a vault read failure is unresolved and names the failure type only`() =
        runBlocking {
            val vault = SecretLookup { _ -> Result.failure(IllegalStateException("token pw-secret leaked?")) }
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains("IllegalStateException"))
            assertFalse(resolution.reason.contains("leaked"))
        }

    @Test
    fun `a vault that throws is unresolved, not a crash`() =
        runBlocking {
            val vault = SecretLookup { _ -> error("down") }
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Unresolved>(resolution)
            Unit
        }

    @Test
    fun `a read failure on the second id fails the whole call, even though the first resolved`() =
        runBlocking {
            val vault =
                SecretLookup { id ->
                    if (id == a) Result.success(record(a)) else Result.failure(IllegalStateException("down"))
                }
            val resolution = SecretReferenceResolver(vault).resolve(setOf(passwordOf(a), passwordOf(b)))
            assertIs<SecretResolution.Unresolved>(resolution)
            assertTrue(resolution.reason.contains("could not be read"), resolution.reason)
        }

    @Test
    fun `ids are matched case-insensitively against the vault`() =
        runBlocking {
            val vault = Vault(listOf(record(a.uppercase(), password = "s3cret!!")))
            val resolution = SecretReferenceResolver(vault).resolve(setOf(SecretReference(a, SecretField.PASSWORD)))
            assertIs<SecretResolution.Resolved>(resolution)
            Unit
        }

    @Test
    fun `no references resolves to nothing without touching the vault`() =
        runBlocking {
            val vault = Vault(emptyList())
            assertIs<SecretResolution.Resolved>(SecretReferenceResolver(vault).resolve(emptySet()))
            assertEquals(emptyList(), vault.asked)
        }
}
