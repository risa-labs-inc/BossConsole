package ai.rever.boss.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks down [GitService.buildCreatePRUrl], the URL builder behind the Create PR
 * action, for branch names that git allows but URLs do not.
 *
 * Git refnames may contain `#`, `&`, `%`, quotes and non-ASCII, and getCreatePRUrl
 * used to interpolate them raw, corrupting the URL:
 * - `feature/#42` produced `.../compare/feature/#42?expand=1`; the `#` started a
 *   fragment, so the browser opened the compare page for `feature/` instead.
 * - `feature&x` produced a phantom `x` query parameter on GitLab and Bitbucket.
 * - non-ASCII branches produced invalid URLs.
 *
 * The branch is now percent-encoded position-aware:
 * - GitHub places the branch in the URL *path*: each `/`-separated segment is
 *   encoded with path rules (space becomes `%20`, never `+`) and `/` stays the
 *   separator, so branches like `feature/login` keep canonical compare URLs.
 * - GitLab and Bitbucket place the branch in the *query string*: the value uses
 *   application/x-www-form-urlencoded rules (space becomes `+`), decoded
 *   server-side.
 *
 * Plain branches (`main`, `feature/login`) must keep producing byte-identical
 * URLs - every expected string below matches the pre-encoding output exactly.
 */
class CreatePRUrlEncodingTest {
    // ==================== GitHub: branch lives in the URL path ====================

    @Test
    fun `github hash is percent-encoded so it cannot start a fragment`() {
        assertEquals(
            "https://github.com/owner/repo/compare/feature/%2342?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "feature/#42"),
        )
    }

    @Test
    fun `github ampersand is percent-encoded in the path`() {
        assertEquals(
            "https://github.com/owner/repo/compare/feature%26x?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "feature&x"),
        )
    }

    @Test
    fun `github percent sign is encoded as percent25 so it survives one decode`() {
        assertEquals(
            "https://github.com/owner/repo/compare/100%25_done?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "100%_done"),
        )
    }

    @Test
    fun `github non-ascii branch is utf-8 percent-encoded in the path`() {
        assertEquals(
            "https://github.com/owner/repo/compare" +
                "/%E5%BC%95%E3%81%A3%E8%B6%8A%E3%81%97?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "引っ越し"),
        )
    }

    @Test
    fun `github double quotes are percent-encoded in the path`() {
        assertEquals(
            "https://github.com/owner/repo/compare/feature/%22typo%22?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "feature/\"typo\""),
        )
    }

    @Test
    fun `github single quote is percent-encoded in the path`() {
        assertEquals(
            "https://github.com/owner/repo/compare/bug%27fix?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "bug'fix"),
        )
    }

    @Test
    fun `github slashes stay as path separators in canonical compare urls`() {
        assertEquals(
            "https://github.com/owner/repo/compare/feature/login?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "feature/login"),
        )
    }

    @Test
    fun `github plain branch keeps the byte-identical legacy URL`() {
        assertEquals(
            "https://github.com/owner/repo/compare/main?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "main"),
        )
    }

    @Test
    fun `github path encoding never emits plus for spaces`() {
        // Defensive: spaces are not legal in git refnames, but the path encoder
        // must never leak the query-rules `+` into a URL path.
        assertEquals(
            "https://github.com/owner/repo/compare/wip%20x?expand=1",
            GitService.buildCreatePRUrl("https://github.com/owner/repo", "wip x"),
        )
    }

    // ==================== GitLab: branch lives in the query string ====================

    @Test
    fun `gitlab hash and slash are percent-encoded in the query value`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=feature%2F%2342",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "feature/#42"),
        )
    }

    @Test
    fun `gitlab ampersand cannot spawn a phantom query parameter`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=feature%26x",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "feature&x"),
        )
    }

    @Test
    fun `gitlab percent sign is encoded as percent25 in the query value`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=100%25_done",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "100%_done"),
        )
    }

    @Test
    fun `gitlab non-ascii branch is utf-8 percent-encoded in the query value`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=%E5%BC%95%E3%81%A3%E8%B6%8A%E3%81%97",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "引っ越し"),
        )
    }

    @Test
    fun `gitlab single quote is percent-encoded in the query value`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=bug%27fix",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "bug'fix"),
        )
    }

    @Test
    fun `gitlab plain branch keeps the byte-identical legacy URL`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=main",
            GitService.buildCreatePRUrl("https://gitlab.com/owner/repo", "main"),
        )
    }

    @Test
    fun `gitlab self-hosted instance gets the same query encoding`() {
        assertEquals(
            "https://gitlab.corp.example/owner/repo/-/merge_requests/new" +
                "?merge_request[source_branch]=feature%26x",
            GitService.buildCreatePRUrl("https://gitlab.corp.example/owner/repo", "feature&x"),
        )
    }

    // ==================== Bitbucket: branch lives in the query string ====================

    @Test
    fun `bitbucket hash and slash are percent-encoded in the query value`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new?source=feature%2F%2342",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "feature/#42"),
        )
    }

    @Test
    fun `bitbucket ampersand cannot spawn a phantom query parameter`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new?source=feature%26x",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "feature&x"),
        )
    }

    @Test
    fun `bitbucket percent sign is encoded as percent25 in the query value`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new?source=100%25_done",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "100%_done"),
        )
    }

    @Test
    fun `bitbucket non-ascii branch is utf-8 percent-encoded in the query value`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new" +
                "?source=%E5%BC%95%E3%81%A3%E8%B6%8A%E3%81%97",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "引っ越し"),
        )
    }

    @Test
    fun `bitbucket double quotes are percent-encoded in the query value`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new?source=feature%2F%22typo%22",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "feature/\"typo\""),
        )
    }

    @Test
    fun `bitbucket plain branch keeps the byte-identical legacy URL`() {
        assertEquals(
            "https://bitbucket.org/owner/repo/pull-requests/new?source=main",
            GitService.buildCreatePRUrl("https://bitbucket.org/owner/repo", "main"),
        )
    }

    // ==================== Unknown provider ====================

    @Test
    fun `unrecognized provider returns null`() {
        assertNull(GitService.buildCreatePRUrl("https://gitee.com/owner/repo", "main"))
    }
}
