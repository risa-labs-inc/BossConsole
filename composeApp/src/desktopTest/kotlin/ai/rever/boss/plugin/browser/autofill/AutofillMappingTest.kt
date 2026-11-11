package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutofillMappingTest {
    private val cardNotes =
        """{"name":"Ada L","exp_month":"07","exp_year":"29","limit_minor":5000,"currency":"USD",""" +
            """"billing":{"line1":"1 Main","city":"X","postal":"1","country":"US"}}"""

    private fun row(
        website: String,
        username: String = "u",
        password: String = "p",
        notes: String? = null,
        tags: List<String> = emptyList(),
        isOwner: Boolean = true,
    ) = SecretEntryWithSharing(
        id = website + username,
        website = website,
        username = username,
        password = password,
        notes = notes,
        tags = tags,
        createdAt = "",
        updatedAt = "",
        isOwner = isOwner,
        accessLevel = if (isOwner) "owner" else "read",
    )

    @Test
    fun `origin normalisation`() {
        assertEquals("https://github.com", AutofillMapping.normalizeOrigin("github.com"))
        assertEquals("https://github.com", AutofillMapping.normalizeOrigin("https://GitHub.com/login?x=1#y"))
        assertEquals("https://www.saucedemo.com", AutofillMapping.normalizeOrigin("https://www.saucedemo.com/"))
        assertEquals("http://localhost:8123", AutofillMapping.normalizeOrigin("http://localhost:8123/login"))
        assertEquals("https://a.com", AutofillMapping.normalizeOrigin("https://a.com:443/"))
        assertEquals("https://a.com:8443", AutofillMapping.normalizeOrigin("a.com:8443"))
        assertEquals("https://the-internet.herokuapp.com", AutofillMapping.normalizeOrigin("the-internet.herokuapp.com/login"))
        assertNull(AutofillMapping.normalizeOrigin("GitHub token"))
        assertNull(AutofillMapping.normalizeOrigin("boss-ai/key"))
        assertNull(AutofillMapping.normalizeOrigin("ftp://a.com"))
        assertNull(AutofillMapping.normalizeOrigin(""))
        assertNull(AutofillMapping.normalizeOrigin("localhostish"))
    }

    @Test
    fun `plain row with a domain is a login`() {
        val item = AutofillMapping.map("github.com/login", "me", "pw", null, listOf("work"))
        assertIs<MirrorItem.Login>(item)
        assertEquals("https://github.com", item.origin)
        assertEquals("pw|https://github.com|me", item.key)
    }

    @Test
    fun `plain row that names no site is skipped`() {
        assertNull(AutofillMapping.map("OpenAI key", "x", "sk", null, emptyList()))
    }

    @Test
    fun `max vault password maps to its site`() {
        val a = AutofillMapping.map("fluck/ws1/vault/united.com", "ada", "pw", "label", listOf("kind:password", "fluck-ws:ws1"))
        assertIs<MirrorItem.Login>(a)
        assertEquals("https://united.com", a.origin)
        val b = AutofillMapping.map("fluck/ws1/vault/clinic-b@flatiron.com", "ada2", "pw", "", listOf("kind:password"))
        assertIs<MirrorItem.Login>(b)
        assertEquals("https://flatiron.com", b.origin)
    }

    @Test
    fun `max vault card maps from pan and notes`() {
        val c = AutofillMapping.map("fluck/ws1/vault/card-4242", "Visa 4242", "4242424242424242", cardNotes, listOf("kind:card", "vault"))
        assertIs<MirrorItem.Card>(c)
        assertEquals(YearMonth.of(2029, 7), c.expiry)
        assertEquals("Ada L", c.holder)
        assertEquals("card|4242|2029-07", c.key)
    }

    @Test
    fun `secrets cvv and connector tokens are skipped`() {
        assertNull(AutofillMapping.map("fluck/ws1/vault/secret-x", "key", "v", "", listOf("kind:secret")))
        assertNull(AutofillMapping.map("fluck/ws1/vault/card-4242-cvv", "cvv", "123", "", listOf("kind:cvv")))
        assertNull(AutofillMapping.map("fluck/ws1/github/GH_TOKEN", "GH_TOKEN", "t", null, emptyList()))
        assertNull(AutofillMapping.map("fluck/ws1/vault/card-4242", "Visa", "4242424242424242", "not json", listOf("kind:card")))
    }

    @Test
    fun `canonical card and address shapes round-trip`() {
        val req = AutofillMapping.cardRequest("4242 4242 4242 4242", YearMonth.of(2031, 3), "Ada")
        assertEquals("card/visa-4242", req.website)
        assertEquals("Visa 4242", req.username)
        assertEquals("4242424242424242", req.password)
        assertEquals(listOf("kind:card"), req.tags)
        val back = AutofillMapping.map(req.website, req.username, req.password, req.notes, req.tags)
        assertIs<MirrorItem.Card>(back)
        assertEquals(YearMonth.of(2031, 3), back.expiry)

        val fields = AddressFields(name = "Ada L", street = "1 Main St", city = "NYC", zip = "10001", country = "US")
        val addr = AutofillMapping.addressRequest(fields, taken = setOf("address/ada-l"))
        assertEquals("address/ada-l-2", addr.website)
        assertEquals(AutofillMapping.ADDRESS_PASSWORD_FILLER, addr.password)
        assertTrue(addr.validate().isSuccess)
        val addrBack = AutofillMapping.map(addr.website, addr.username, addr.password, addr.notes, addr.tags)
        assertIs<MirrorItem.Address>(addrBack)
        assertEquals(fields, addrBack.fields)

        val login = AutofillMapping.loginRequest("https://a.com", "me", "pw")
        assertEquals(listOf("kind:password"), login.tags)
        assertIs<MirrorItem.Login>(AutofillMapping.map(login.website, login.username, login.password, login.notes, login.tags))
    }

    @Test
    fun `owned row wins over a shared duplicate`() {
        val items =
            AutofillMapping.map(
                listOf(
                    row("a.com", "me", "shared-pw", isOwner = false),
                    row("https://a.com/x", "me", "own-pw", isOwner = true),
                ),
            )
        assertEquals(1, items.size)
        assertEquals("own-pw", (items.values.single() as MirrorItem.Login).password)
    }

    @Test
    fun `values never appear in toString`() {
        val l = MirrorItem.Login("https://a.com", "me", "hunter2")
        assertTrue("hunter2" !in l.toString())
        val c = MirrorItem.Card("4242424242424242", YearMonth.of(2030, 1), "A")
        assertTrue("424242424242" !in c.toString())
    }

    @Test
    fun `expiry parsing`() {
        assertEquals(YearMonth.of(2029, 7), AutofillMapping.parseExpiry("7", "29"))
        assertEquals(YearMonth.of(2031, 12), AutofillMapping.parseExpiry("12", "2031"))
        assertNull(AutofillMapping.parseExpiry("13", "29"))
        assertNull(AutofillMapping.parseExpiry("", "29"))
    }
}
