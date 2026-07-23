package ai.rever.boss.git

import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks down [GitService]'s pure git-porcelain parsers, which every git panel
 * (status list, commit log, stash list, PR-link building) renders from:
 *
 * - [GitService.parseStatusLine] / [GitService.parseStatusChar] — `git status --porcelain=v1`
 * - [GitService.parseCommitLine] — `git log --format=%H%x00%h%x00…` (NUL-separated)
 * - [GitService.parseStashLine] — `git stash list`
 * - [GitService.parseRemoteUrl] — remote URL → https URL for "Create PR"
 *
 * These were `private`; they are now `internal` (visibility-only change) so the
 * tests can feed them raw porcelain lines without spawning git processes.
 */
class GitPorcelainParserTest {

    // ==================== parseStatusChar ====================

    @Test
    fun `parseStatusChar maps every documented porcelain code`() {
        assertEquals(GitFileStatusType.MODIFIED, GitService.parseStatusChar('M'))
        assertEquals(GitFileStatusType.ADDED, GitService.parseStatusChar('A'))
        assertEquals(GitFileStatusType.DELETED, GitService.parseStatusChar('D'))
        assertEquals(GitFileStatusType.RENAMED, GitService.parseStatusChar('R'))
        assertEquals(GitFileStatusType.COPIED, GitService.parseStatusChar('C'))
        assertEquals(GitFileStatusType.UNTRACKED, GitService.parseStatusChar('?'))
        assertEquals(GitFileStatusType.IGNORED, GitService.parseStatusChar('!'))
        assertEquals(GitFileStatusType.UNMERGED, GitService.parseStatusChar('U'))
    }

    @Test
    fun `parseStatusChar returns null for space (no status)`() {
        assertNull(GitService.parseStatusChar(' '))
    }

    @Test
    fun `parseStatusChar returns null for unknown codes`() {
        // 'T' (type change) is a real porcelain v1 code the parser deliberately
        // does not model; unknown codes degrade to "no status" instead of crashing.
        assertNull(GitService.parseStatusChar('T'))
        assertNull(GitService.parseStatusChar('X'))
        assertNull(GitService.parseStatusChar('z'))
    }

    // ==================== parseStatusLine: normal statuses ====================

    @Test
    fun `staged modification - index M, clean worktree`() {
        val status = GitService.parseStatusLine("M  src/App.kt")
        assertNotNull(status)
        assertEquals("src/App.kt", status.path)
        assertEquals(GitFileStatusType.MODIFIED, status.indexStatus)
        assertNull(status.workTreeStatus)
        assertTrue(status.isStaged)
        assertFalse(status.isUnstaged)
        assertNull(status.originalPath)
    }

    @Test
    fun `unstaged modification - clean index, worktree M`() {
        val status = GitService.parseStatusLine(" M src/App.kt")
        assertNotNull(status)
        assertEquals("src/App.kt", status.path)
        assertNull(status.indexStatus)
        assertEquals(GitFileStatusType.MODIFIED, status.workTreeStatus)
        assertFalse(status.isStaged)
        assertTrue(status.isUnstaged)
    }

    @Test
    fun `staged plus unstaged combo - MM`() {
        val status = GitService.parseStatusLine("MM src/App.kt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.MODIFIED, status.indexStatus)
        assertEquals(GitFileStatusType.MODIFIED, status.workTreeStatus)
        assertTrue(status.isStaged)
        assertTrue(status.isUnstaged)
    }

    @Test
    fun `staged add then modified in worktree - AM`() {
        val status = GitService.parseStatusLine("AM new-file.txt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.ADDED, status.indexStatus)
        assertEquals(GitFileStatusType.MODIFIED, status.workTreeStatus)
        assertTrue(status.isStaged)
        assertTrue(status.isUnstaged)
    }

    @Test
    fun `staged deletion - D in index`() {
        val status = GitService.parseStatusLine("D  gone.txt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.DELETED, status.indexStatus)
        assertNull(status.workTreeStatus)
        assertTrue(status.isStaged)
        assertFalse(status.isUnstaged)
    }

    @Test
    fun `untracked file - question marks`() {
        val status = GitService.parseStatusLine("?? notes.md")
        assertNotNull(status)
        assertEquals("notes.md", status.path)
        assertEquals(GitFileStatusType.UNTRACKED, status.indexStatus)
        assertEquals(GitFileStatusType.UNTRACKED, status.workTreeStatus)
        // UNTRACKED is explicitly carved out of "staged".
        assertFalse(status.isStaged)
        assertTrue(status.isUnstaged)
    }

    @Test
    fun `ignored file - exclamation marks parse to IGNORED`() {
        val status = GitService.parseStatusLine("!! build/")
        assertNotNull(status)
        assertEquals("build/", status.path)
        assertEquals(GitFileStatusType.IGNORED, status.indexStatus)
        assertEquals(GitFileStatusType.IGNORED, status.workTreeStatus)
    }

    @Disabled(
        "documents bug: '!!' (ignored) sets isStaged=true because only UNTRACKED is " +
            "carved out of the isStaged computation; an ignored file is never staged. " +
            "Currently unreachable in production (getStatus never passes --ignored)."
    )
    @Test
    fun `ignored file is not staged`() {
        val status = GitService.parseStatusLine("!! build/")
        assertNotNull(status)
        assertFalse(status.isStaged)
    }

    // ==================== parseStatusLine: renames and copies ====================

    @Test
    fun `staged rename with arrow keeps both paths`() {
        val status = GitService.parseStatusLine("R  old/name.kt -> new/name.kt")
        assertNotNull(status)
        assertEquals("new/name.kt", status.path)
        assertEquals("old/name.kt", status.originalPath)
        assertEquals(GitFileStatusType.RENAMED, status.indexStatus)
        assertNull(status.workTreeStatus)
        assertTrue(status.isStaged)
        assertFalse(status.isUnstaged)
    }

    @Test
    fun `staged copy with arrow keeps both paths`() {
        val status = GitService.parseStatusLine("C  src/a.kt -> src/b.kt")
        assertNotNull(status)
        assertEquals("src/b.kt", status.path)
        assertEquals("src/a.kt", status.originalPath)
        assertEquals(GitFileStatusType.COPIED, status.indexStatus)
    }

    @Test
    fun `rename then modified in worktree - RM`() {
        val status = GitService.parseStatusLine("RM old.txt -> new.txt")
        assertNotNull(status)
        assertEquals("new.txt", status.path)
        assertEquals("old.txt", status.originalPath)
        assertEquals(GitFileStatusType.RENAMED, status.indexStatus)
        assertEquals(GitFileStatusType.MODIFIED, status.workTreeStatus)
        assertTrue(status.isStaged)
        assertTrue(status.isUnstaged)
    }

    // ==================== parseStatusLine: path shapes ====================

    @Test
    fun `path with spaces passes through verbatim`() {
        // porcelain v1 does not quote paths for spaces.
        val status = GitService.parseStatusLine(" M My Documents/read me.txt")
        assertNotNull(status)
        assertEquals("My Documents/read me.txt", status.path)
    }

    @Test
    fun `unicode path passes through verbatim`() {
        // With core.quotePath=false git emits raw UTF-8; the parser must not mangle it.
        val status = GitService.parseStatusLine("A  docs/日本語 déjà ✨.md")
        assertNotNull(status)
        assertEquals("docs/日本語 déjà ✨.md", status.path)
        assertEquals(GitFileStatusType.ADDED, status.indexStatus)
    }

    @Test
    fun `C-quoted path is passed through verbatim, not unquoted`() {
        // Documents the parser's contract: with core.quotePath=true git C-quotes
        // special paths (e.g. "a\"b.txt"); the parser performs no unquoting and the
        // quoted token flows through as the path (display-level limitation).
        val status = GitService.parseStatusLine("M  \"a\\\"b.txt\"")
        assertNotNull(status)
        assertEquals("\"a\\\"b.txt\"", status.path)
    }

    @Test
    fun `rename of path with spaces on both sides`() {
        val status = GitService.parseStatusLine("R  old dir/a file.txt -> new dir/a file.txt")
        assertNotNull(status)
        assertEquals("new dir/a file.txt", status.path)
        assertEquals("old dir/a file.txt", status.originalPath)
    }

    // ==================== parseStatusLine: merge conflicts ====================

    @Test
    fun `both modified conflict - UU`() {
        val status = GitService.parseStatusLine("UU src/conflict.kt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.UNMERGED, status.indexStatus)
        assertEquals(GitFileStatusType.UNMERGED, status.workTreeStatus)
        assertTrue(status.isUnstaged)
    }

    @Test
    fun `both added conflict - AA`() {
        val status = GitService.parseStatusLine("AA both-added.txt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.ADDED, status.indexStatus)
        assertEquals(GitFileStatusType.ADDED, status.workTreeStatus)
    }

    @Test
    fun `both deleted conflict - DD`() {
        val status = GitService.parseStatusLine("DD both-deleted.txt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.DELETED, status.indexStatus)
        assertEquals(GitFileStatusType.DELETED, status.workTreeStatus)
    }

    @Test
    fun `deleted by them - UD`() {
        val status = GitService.parseStatusLine("UD theirs-deleted.txt")
        assertNotNull(status)
        assertEquals(GitFileStatusType.UNMERGED, status.indexStatus)
        assertEquals(GitFileStatusType.DELETED, status.workTreeStatus)
    }

    // ==================== parseStatusLine: empty and malformed ====================

    @Test
    fun `empty and too-short lines return null`() {
        assertNull(GitService.parseStatusLine(""))
        assertNull(GitService.parseStatusLine("M"))
        assertNull(GitService.parseStatusLine("M "))
    }

    @Test
    fun `line with unknown status codes degrades to no-status, never crashes`() {
        val status = GitService.parseStatusLine("XY weird.txt")
        assertNotNull(status)
        assertEquals("weird.txt", status.path)
        assertNull(status.indexStatus)
        assertNull(status.workTreeStatus)
        assertFalse(status.isStaged)
        assertFalse(status.isUnstaged)
    }

    // ==================== parseCommitLine ====================

    private val nul = "\u0000"

    private fun commitLine(
        hash: String = "a".repeat(40),
        short: String = "aaaaaaa",
        author: String = "Jane Doe",
        email: String = "jane@example.com",
        timestamp: String = "1721721600",
        subject: String = "Fix the thing",
        parents: String? = "b".repeat(40),
        refs: String? = ""
    ): String {
        val fields = mutableListOf(hash, short, author, email, timestamp, subject)
        if (parents != null) fields.add(parents)
        if (refs != null) fields.add(refs)
        return fields.joinToString(nul)
    }

    @Test
    fun `parses a full commit line field by field`() {
        val commit = GitService.parseCommitLine(
            commitLine(refs = "HEAD -> main, origin/main, tag: v1.2.3")
        )
        assertNotNull(commit)
        assertEquals("a".repeat(40), commit.hash)
        assertEquals("aaaaaaa", commit.shortHash)
        assertEquals("Jane Doe", commit.author)
        assertEquals("jane@example.com", commit.authorEmail)
        assertEquals(1721721600L, commit.date)
        assertEquals("Fix the thing", commit.subject)
        assertEquals(listOf("b".repeat(40)), commit.parentHashes)
        assertEquals(listOf("HEAD -> main", "origin/main", "tag: v1.2.3"), commit.refs)
    }

    @Test
    fun `subject with pipes, colons, arrows and emoji survives because separator is NUL`() {
        val subject = "feat(ui): a | b -> c 🎉 \"quoted\" 100%"
        val commit = GitService.parseCommitLine(commitLine(subject = subject))
        assertNotNull(commit)
        assertEquals(subject, commit.subject)
    }

    @Test
    fun `root commit has no parents`() {
        val commit = GitService.parseCommitLine(commitLine(parents = ""))
        assertNotNull(commit)
        assertTrue(commit.parentHashes.isEmpty())
    }

    @Test
    fun `merge commit splits parent hashes on space`() {
        val p1 = "1".repeat(40)
        val p2 = "2".repeat(40)
        val commit = GitService.parseCommitLine(commitLine(parents = "$p1 $p2"))
        assertNotNull(commit)
        assertEquals(listOf(p1, p2), commit.parentHashes)
    }

    @Test
    fun `commit with no refs yields empty ref list`() {
        val commit = GitService.parseCommitLine(commitLine(refs = ""))
        assertNotNull(commit)
        assertTrue(commit.refs.isEmpty())
    }

    @Test
    fun `line with only six fields still parses with empty parents and refs`() {
        val commit = GitService.parseCommitLine(commitLine(parents = null, refs = null))
        assertNotNull(commit)
        assertTrue(commit.parentHashes.isEmpty())
        assertTrue(commit.refs.isEmpty())
    }

    @Test
    fun `non-numeric timestamp degrades to zero`() {
        val commit = GitService.parseCommitLine(commitLine(timestamp = "not-a-number"))
        assertNotNull(commit)
        assertEquals(0L, commit.date)
    }

    @Test
    fun `lines with fewer than six fields return null`() {
        assertNull(GitService.parseCommitLine(""))
        assertNull(GitService.parseCommitLine("just some text"))
        assertNull(GitService.parseCommitLine(listOf("h", "s", "a", "e", "1").joinToString(nul)))
    }

    // ==================== parseStashLine ====================

    @Test
    fun `WIP stash line extracts index, branch and message`() {
        val stash = GitService.parseStashLine(0, "stash@{0}: WIP on main: 1a2b3c4 initial commit")
        assertNotNull(stash)
        assertEquals(0, stash.index)
        assertEquals("main", stash.branch)
        assertEquals("1a2b3c4 initial commit", stash.message)
    }

    @Test
    fun `named stash line (On branch) extracts branch and message`() {
        val stash = GitService.parseStashLine(1, "stash@{1}: On feature/login: saved experiment")
        assertNotNull(stash)
        assertEquals(1, stash.index)
        assertEquals("feature/login", stash.branch)
        assertEquals("saved experiment", stash.message)
    }

    @Test
    fun `index parsed from the line wins over the list position`() {
        val stash = GitService.parseStashLine(0, "stash@{7}: On main: deep stash")
        assertNotNull(stash)
        assertEquals(7, stash.index)
    }

    @Test
    fun `message containing colons is kept intact`() {
        val stash = GitService.parseStashLine(0, "stash@{0}: On main: fix: parser: edge case")
        assertNotNull(stash)
        assertEquals("main", stash.branch)
        assertEquals("fix: parser: edge case", stash.message)
    }

    @Test
    fun `stash without branch prefix keeps whole remainder as message`() {
        val stash = GitService.parseStashLine(0, "stash@{0}: autostash")
        assertNotNull(stash)
        assertNull(stash.branch)
        assertEquals("autostash", stash.message)
    }

    @Test
    fun `detached-head stash falls back to branchless message`() {
        // "(no branch)" contains a space, so the branch group (\S+?) cannot match;
        // the parser degrades gracefully to a null branch with the full remainder.
        val stash = GitService.parseStashLine(0, "stash@{0}: WIP on (no branch): abc1234 subject")
        assertNotNull(stash)
        assertNull(stash.branch)
        assertEquals("WIP on (no branch): abc1234 subject", stash.message)
    }

    @Test
    fun `non-stash lines return null`() {
        assertNull(GitService.parseStashLine(0, ""))
        assertNull(GitService.parseStashLine(0, "not a stash line"))
        assertNull(GitService.parseStashLine(0, "stash@{x}: On main: bad index"))
    }

    // ==================== parseRemoteUrl (sibling pure helper) ====================

    @Test
    fun `scp-style ssh remote becomes https`() {
        assertEquals(
            "https://github.com/owner/repo",
            GitService.parseRemoteUrl("git@github.com:owner/repo.git")
        )
    }

    @Test
    fun `scp-style ssh remote with nested groups`() {
        assertEquals(
            "https://gitlab.com/group/subgroup/repo",
            GitService.parseRemoteUrl("git@gitlab.com:group/subgroup/repo.git")
        )
    }

    @Test
    fun `ssh protocol remote becomes https`() {
        assertEquals(
            "https://github.com/owner/repo",
            GitService.parseRemoteUrl("ssh://git@github.com/owner/repo.git")
        )
    }

    @Test
    fun `https remote just loses the git suffix`() {
        assertEquals(
            "https://github.com/owner/repo",
            GitService.parseRemoteUrl("https://github.com/owner/repo.git")
        )
        assertEquals(
            "https://github.com/owner/repo",
            GitService.parseRemoteUrl("https://github.com/owner/repo")
        )
    }

    @Test
    fun `http remote is kept as http`() {
        assertEquals(
            "http://git.internal/owner/repo",
            GitService.parseRemoteUrl("http://git.internal/owner/repo.git")
        )
    }

    @Test
    fun `unsupported remote formats return null`() {
        assertNull(GitService.parseRemoteUrl("file:///srv/git/repo.git"))
        assertNull(GitService.parseRemoteUrl("/srv/git/repo.git"))
        assertNull(GitService.parseRemoteUrl(""))
    }
}
