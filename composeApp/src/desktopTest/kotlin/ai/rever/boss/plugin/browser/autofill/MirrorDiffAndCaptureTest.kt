package ai.rever.boss.plugin.browser.autofill

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorDiffAndCaptureTest {
    private fun login(
        host: String,
        user: String,
        pw: String = "p",
    ) = MirrorItem.Login("https://$host", user, pw)

    @Test
    fun `new items are written and stale owned items removed`() {
        val a = login("a.com", "me")
        val plan =
            MirrorDiff.plan(
                desired = mapOf(a.key to a),
                present = setOf("pw|https://old.com|me"),
                owned = setOf("pw|https://old.com|me"),
                fingerprints = emptyMap(),
            )
        assertEquals(setOf("pw|https://old.com|me"), plan.remove)
        assertEquals(listOf(a.key), plan.write.map { it.key })
    }

    @Test
    fun `records the mirror did not add are never touched`() {
        val a = login("a.com", "me")
        val plan =
            MirrorDiff.plan(
                desired = mapOf(a.key to a),
                present = setOf(a.key, "pw|https://users-own.com|x"),
                owned = emptySet(),
                fingerprints = emptyMap(),
            )
        assertTrue(plan.remove.isEmpty())
        assertTrue(plan.write.isEmpty())
        assertEquals(setOf(a.key), plan.skippedForeign)
    }

    @Test
    fun `unchanged owned items are skipped and changed ones rewritten`() {
        val a = login("a.com", "me", "v1")
        val same = MirrorDiff.plan(mapOf(a.key to a), setOf(a.key), setOf(a.key), mapOf(a.key to a.fingerprint))
        assertTrue(same.write.isEmpty())
        val changed = login("a.com", "me", "v2")
        val old = mapOf(a.key to a.fingerprint)
        val plan = MirrorDiff.plan(mapOf(changed.key to changed), setOf(a.key), setOf(a.key), old)
        assertEquals(listOf(changed.key), plan.write.map { it.key })
        // Owned but deleted from Chromium by hand: written back.
        val gone = MirrorDiff.plan(mapOf(a.key to a), emptySet(), setOf(a.key), mapOf(a.key to a.fingerprint))
        assertEquals(listOf(a.key), gone.write.map { it.key })
    }

    @Test
    fun `everything owned goes when SM is empty`() {
        val present = setOf("pw|https://a.com|me", "pw|https://b.com|x")
        val plan = MirrorDiff.plan(emptyMap(), present, setOf("pw|https://a.com|me"), emptyMap())
        assertEquals(setOf("pw|https://a.com|me"), plan.remove)
    }

    @Test
    fun `capture pairs by origin and login`() {
        var now = 1_000L
        val c = CredentialCapture(clock = { now })
        c.record("https://www.saucedemo.com/", "standard_user", "secret_sauce")
        c.record("https://other.com/login", "standard_user", "nope")
        assertNull(c.take("https://www.saucedemo.com/inventory.html", "problem_user"))
        val hit = c.take("https://www.saucedemo.com/inventory.html", "Standard_User ")
        assertNotNull(hit)
        assertEquals("secret_sauce", hit.password)
        assertNull(c.take("https://www.saucedemo.com/", "standard_user"), "taken once")
        c.record("https://a.com", "me", "x")
        now += CredentialCapture.TTL_MS + 1
        assertNull(c.take("https://a.com", "me"), "expired")
    }

    @Test
    fun `latest capture for the same login wins`() {
        val c = CredentialCapture(clock = { 0L })
        c.record("https://a.com/login", "me", "old")
        c.record("https://a.com/login", "me", "new")
        assertEquals("new", c.take("https://a.com/", "me")?.password)
    }

    @Test
    fun `capture payload parsing`() {
        assertEquals("u" to "p", CredentialCapture.parse("""{"u":"u","p":"p"}"""))
        assertEquals("" to "p", CredentialCapture.parse("""{"p":"p"}"""))
        assertNull(CredentialCapture.parse("""{"u":"u"}"""))
        assertNull(CredentialCapture.parse("nope"))
    }

    @Test
    fun `index persists keys only`() {
        val dir = createTempDirectory("autofill-index").toFile()
        val f = File(dir, "i.json")
        val idx = MirrorIndex(f)
        idx.add("/p", "pw|https://a.com|me")
        idx.add("/p", "card|4242|2030-01")
        idx.save()
        val again = MirrorIndex(f)
        assertEquals(setOf("pw|https://a.com|me", "card|4242|2030-01"), again.keys("/p"))
        again.clear("/p")
        again.save()
        assertTrue(MirrorIndex(f).keys("/p").isEmpty())
        dir.deleteRecursively()
    }
}

class PluginStandDownCheckTest {
    private fun jar(classText: String): File {
        val f = File.createTempFile("fluck-browser", ".jar")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("ai/rever/boss/plugin/dynamic/fluckbrowser/X.class"))
            z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()) + classText.toByteArray() + byteArrayOf(0))
            z.closeEntry()
        }
        return f
    }

    @Test
    fun `a deferring build is recognised from its classes, whatever its version`() {
        val deferring = jar("boss.browser.nativeAutofill")
        val old = jar("boss.fluck.suggestPasswords")
        assertTrue(PluginStandDownCheck.honoursFlag(deferring, "1.2.35"))
        assertTrue(!PluginStandDownCheck.honoursFlag(old, "1.2.35"))
        deferring.delete()
        old.delete()
    }

    @Test
    fun `version is the fallback when the jar cannot be read`() {
        assertTrue(!PluginStandDownCheck.honoursFlag(null, "1.2.35"))
        assertTrue(PluginStandDownCheck.honoursFlag(null, "1.2.36"))
        assertTrue(PluginStandDownCheck.honoursFlag(null, "1.3.0"))
        assertTrue(!PluginStandDownCheck.honoursFlag(null, null))
        assertTrue(!PluginStandDownCheck.honoursFlag(File("/nonexistent.jar"), "1.2.35"))
    }
}
