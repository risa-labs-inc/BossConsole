package ai.rever.boss.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GitRefreshConcurrencyTest {
    @AfterEach
    fun cleanUp() {
        GitService.clear()
    }

    @Test
    fun `concurrent refresh calls execute serially without interleaving state`() =
        runBlocking {
            val nonRepoDirA = File(System.getProperty("java.io.tmpdir"), "git-refresh-test-a").apply { mkdirs() }
            val nonRepoDirB = File(System.getProperty("java.io.tmpdir"), "git-refresh-test-b").apply { mkdirs() }

            try {
                val jobA = async(Dispatchers.IO) { GitService.refresh(nonRepoDirA.absolutePath) }
                val jobB = async(Dispatchers.IO) { GitService.refresh(nonRepoDirB.absolutePath) }
                awaitAll(jobA, jobB)

                val finalPath = GitService.getCurrentProjectPath()
                assert(finalPath == nonRepoDirA.absolutePath || finalPath == nonRepoDirB.absolutePath) {
                    "Current project path should be one of the refreshed paths, got: $finalPath"
                }
                assertFalse(GitService.isGitRepository.value)
                assertNull(GitService.currentBranch.value)
            } finally {
                nonRepoDirA.deleteRecursively()
                nonRepoDirB.deleteRecursively()
            }
        }

    @Test
    fun `clear resets state holding refresh mutex`() =
        runBlocking {
            val nonRepoDir = File(System.getProperty("java.io.tmpdir"), "git-refresh-test-clear").apply { mkdirs() }
            try {
                GitService.refresh(nonRepoDir.absolutePath)
                GitService.clear()

                assertNull(GitService.getCurrentProjectPath())
                assertFalse(GitService.isGitRepository.value)
                assertNull(GitService.currentBranch.value)
            } finally {
                nonRepoDir.deleteRecursively()
            }
        }
}
