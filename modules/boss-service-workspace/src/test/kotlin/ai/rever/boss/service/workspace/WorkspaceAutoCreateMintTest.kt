package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.LoadWorkspaceRequest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A same-millisecond auto-create mints two records, not one record twice.
 *
 * `loadWorkspace` auto-creates a record for an unknown project path under the ID
 * `workspace-<millis>` with no existence check. Under the mutation lock that ID used to be
 * re-derivable: two DIFFERENT paths opened in the same millisecond minted the same ID, and the
 * second save - a temp-file plus REPLACE_EXISTING move onto the same target - silently replaced
 * the first record on disk and in memory. One project's workspace simply stopped existing.
 *
 * The clock is pinned through the `nowMillis` seam so the collision is not left to a fast
 * machine's chance; `uniqueNowMillis` must bump past anything the registry already holds.
 */
class WorkspaceAutoCreateMintTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun open(path: String) = LoadWorkspaceRequest.newBuilder().setProjectPath(path).build()

    @Test
    fun `same-millisecond auto-creates mint distinct records that both survive`() =
        runBlocking {
            val root = temporary.newFolder()
            val service = WorkspaceServiceImpl(root)
            service.nowMillis = { 1_700_000_000_000 }

            val alpha = service.loadWorkspace(open("/projects/alpha")).workspace
            val beta = service.loadWorkspace(open("/projects/beta")).workspace

            assertNotEquals(alpha.id, beta.id, "two paths opened in one millisecond minted one ID")
            assertTrue(Files.isRegularFile(root.toPath().resolve("${alpha.id}.json")))
            assertTrue(Files.isRegularFile(root.toPath().resolve("${beta.id}.json")))

            val listed = service.getWorkspaces(Empty.getDefaultInstance()).workspacesList
            assertEquals(2, listed.size, "the second open replaced the first record instead of adding one")
            assertEquals("/projects/alpha", listed.single { it.id == alpha.id }.projectPath)
            assertEquals("/projects/beta", listed.single { it.id == beta.id }.projectPath)

            val reloaded = WorkspaceServiceImpl(root).getWorkspaces(Empty.getDefaultInstance()).workspacesList
            assertEquals(setOf(alpha.id, beta.id), reloaded.map { it.id }.toSet())
        }
}
