package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins down the one-time cleanup that collapses the duplicate entries left by the browser
 * plugin recording URL bar text: history holds both what the user typed
 * (`https://youtube.com`, no title) and what the browser committed
 * (`https://www.youtube.com/`, "YouTube"), which showed up as two suggestions for one
 * site with their visit counts split between them.
 */
class UrlHistoryMergeTest {
    private fun entry(
        url: String,
        title: String = "",
        visits: Int = 1,
        lastVisited: Long = 0,
    ) = UrlHistoryEntry(
        url = url,
        title = title,
        domain = url.substringAfter("://").substringBefore('/'),
        visitCount = visits,
        lastVisited = lastVisited,
    )

    @Test
    fun `the entry that knows the title wins and the counts add up`() {
        val merged =
            mergeDuplicateHistoryEntries(
                listOf(
                    entry("https://youtube.com", visits = 7, lastVisited = 200),
                    entry("https://www.youtube.com/", title = "YouTube", visits = 48, lastVisited = 100),
                ),
            )

        assertEquals(1, merged.size)
        val only = merged.single()
        assertEquals("https://www.youtube.com/", only.url)
        assertEquals("YouTube", only.title)
        assertEquals(55, only.visitCount)
        // The later visit survives even though it came from the entry that lost.
        assertEquals(200, only.lastVisited)
    }

    @Test
    fun `hash-routed views of one path stay separate`() {
        val entries =
            listOf(
                entry("https://mail.google.com/mail/u/0/#inbox", title = "Inbox"),
                entry("https://mail.google.com/mail/u/0/#sent", title = "Sent"),
                entry("https://mail.google.com/mail/u/0/#drafts", title = "Drafts"),
            )

        assertEquals(3, mergeDuplicateHistoryEntries(entries).size)
    }

    @Test
    fun `different pages on a host are untouched`() {
        val entries =
            listOf(
                entry("https://example.com/a", title = "A"),
                entry("https://example.com/b", title = "B"),
                entry("https://example.com/a?q=1", title = "A search"),
            )

        assertEquals(3, mergeDuplicateHistoryEntries(entries).size)
    }

    @Test
    fun `a history with nothing to merge comes back unchanged`() {
        val entries = listOf(entry("https://example.com/", title = "Example", visits = 3))

        assertEquals(entries, mergeDuplicateHistoryEntries(entries))
    }

    @Test
    fun `the https spelling wins even when the plaintext twin has the visits`() {
        // The merged URL is one we will navigate to, so it must not downgrade the user
        // to http just because the old entry was visited more often.
        val merged =
            mergeDuplicateHistoryEntries(
                listOf(
                    entry("http://example.com/app", title = "App", visits = 40),
                    entry("https://example.com/app", title = "App", visits = 2),
                ),
            )

        assertEquals("https://example.com/app", merged.single().url)
        assertEquals(42, merged.single().visitCount)
    }

    @Test
    fun `when no entry has a title the most visited one represents the group`() {
        val merged =
            mergeDuplicateHistoryEntries(
                listOf(
                    entry("https://example.com", visits = 2),
                    entry("https://www.example.com/", visits = 9),
                ),
            )

        assertEquals("https://www.example.com/", merged.single().url)
        assertEquals(11, merged.single().visitCount)
    }
}
