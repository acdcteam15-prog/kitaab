package com.pdfreader.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {

    private fun release(
        body: String = "Fixes and a new reader.",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """
            {"name":"Kitaab-1_2_0-build1007.apk","size":15728640,"url":"https://api.github.com/repos/o/r/releases/assets/11","browser_download_url":"https://github.com/o/r/releases/download/v1.2.0-build1007/Kitaab-1_2_0-build1007.apk"},
            {"name":"Kitaab-1_2_0-build1007.apk.sha256","size":100,"url":"https://api.github.com/repos/o/r/releases/assets/12","browser_download_url":"https://github.com/o/r/releases/download/v1.2.0-build1007/Kitaab-1_2_0-build1007.apk.sha256"}
        """.trimIndent()
    ): String = """
        {"tag_name":"v1.2.0-build1007","html_url":"https://github.com/o/r/releases/tag/v1.2.0-build1007",
         "published_at":"2026-11-02T10:00:00Z","draft":$draft,"prerelease":$prerelease,
         "body":${quote(body)},"assets":[$assets]}
    """.trimIndent()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ---- file name -------------------------------------------------------------------------------
    @Test fun apkName_parsesVersionAndCode() {
        val n = UpdateParser.parseApkName("Kitaab-1_2_0-build1007.apk")
        assertNotNull(n)
        assertEquals("Kitaab", n!!.app)
        assertEquals("1.2.0", n.versionName)
        assertEquals(1007L, n.versionCode)
    }

    @Test fun apkName_rejectsOtherFiles() {
        assertNull(UpdateParser.parseApkName("Kitaab.apk"))
        assertNull(UpdateParser.parseApkName("Kitaab-1_2_0-build.apk"))
        assertNull(UpdateParser.parseApkName("Kitaab-1_2_0-build12.apk.sha256"))
        assertNull(UpdateParser.parseApkName("notes.txt"))
    }

    // ---- required marker and notes ---------------------------------------------------------------
    @Test fun required_markerAnywhereAnyCase() {
        assertTrue(UpdateParser.isRequired("Security fix [required]"))
        assertTrue(UpdateParser.isRequired("[REQUIRED]\nplease update"))
        assertFalse(UpdateParser.isRequired("Nothing special"))
        assertFalse(UpdateParser.isRequired(null))
    }

    @Test fun notes_markerRemovedAndTrimmed() {
        assertEquals("Security fix", UpdateParser.cleanNotes("  Security fix [required]\r\n"))
        assertEquals("", UpdateParser.cleanNotes(null))
    }

    @Test fun notes_areCapped() {
        val long = "x".repeat(6000)
        val cleaned = UpdateParser.cleanNotes(long)
        assertTrue(cleaned.length <= 4001)
        assertTrue(cleaned.endsWith("\u2026"))
    }

    // ---- checksum --------------------------------------------------------------------------------
    @Test fun sha256_readsSha256sumFormat() {
        val h = "ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789"
        assertEquals(h.lowercase(), UpdateParser.parseSha256("$h  Kitaab-1_2_0-build1007.apk\n"))
        assertEquals(h.lowercase(), UpdateParser.parseSha256(h))
    }

    @Test fun sha256_rejectsShortOrLongRuns() {
        assertNull(UpdateParser.parseSha256("abc123"))
        assertNull(UpdateParser.parseSha256("a".repeat(65)))
        assertNull(UpdateParser.parseSha256(""))
        assertNull(UpdateParser.parseSha256(null))
    }

    // ---- release JSON ----------------------------------------------------------------------------
    @Test fun release_parsesNormalRelease() {
        val info = UpdateParser.parseRelease(release(body = "What is new\n- Scan tools [required]"))
        assertNotNull(info)
        info!!
        assertEquals("1.2.0", info.versionName)
        assertEquals(1007L, info.versionCode)
        assertTrue(info.required)
        assertEquals("What is new\n- Scan tools", info.notes)
        assertEquals("Kitaab-1_2_0-build1007.apk", info.apk.name)
        assertEquals(15728640L, info.apk.size)
        assertEquals("Kitaab-1_2_0-build1007.apk.sha256", info.sha256Asset?.name)
        assertEquals("v1.2.0-build1007", info.tag)
    }

    @Test fun release_draftAndPrereleaseAreIgnored() {
        assertNull(UpdateParser.parseRelease(release(draft = true)))
        assertNull(UpdateParser.parseRelease(release(prerelease = true)))
    }

    @Test fun release_withoutMatchingApkIsNull() {
        assertNull(UpdateParser.parseRelease(release(assets = """{"name":"source.zip","size":1,"url":"u","browser_download_url":"b"}""")))
        assertNull(UpdateParser.parseRelease(release(assets = "")))
    }

    @Test fun release_highestBuildWinsAmongSeveralApks() {
        val assets = """
            {"name":"Kitaab-1_1_0-build1001.apk","size":1,"url":"u1","browser_download_url":"b1"},
            {"name":"Kitaab-1_2_0-build1007.apk","size":2,"url":"u2","browser_download_url":"b2"}
        """.trimIndent()
        val info = UpdateParser.parseRelease(release(assets = assets))
        assertEquals(1007L, info!!.versionCode)
        assertNull(info.sha256Asset)
    }

    @Test fun release_brokenJsonNeverThrows() {
        assertNull(UpdateParser.parseRelease(null))
        assertNull(UpdateParser.parseRelease(""))
        assertNull(UpdateParser.parseRelease("not json"))
        assertNull(UpdateParser.parseRelease("[1,2,3]"))
        assertNull(UpdateParser.parseRelease("{\"assets\": 5}"))
    }

    @Test fun release_nullFieldsAreTolerated() {
        val json = """{"tag_name":null,"body":null,"draft":null,"assets":[{"name":"Kitaab-1_0_1-build1002.apk","size":null,"url":null,"browser_download_url":"b"}]}"""
        val info = UpdateParser.parseRelease(json)
        assertNotNull(info)
        assertEquals(1002L, info!!.versionCode)
        assertEquals("", info.notes)
        assertFalse(info.required)
    }

    // ---- offer / skip ----------------------------------------------------------------------------
    private fun info(code: Long, required: Boolean = false) = UpdateParser.parseRelease(
        release(body = if (required) "x [required]" else "x",
            assets = """{"name":"Kitaab-1_0_0-build$code.apk","size":1,"url":"u","browser_download_url":"b"}""")
    )!!

    @Test fun offer_onlyWhenNewer() {
        assertTrue(UpdateParser.shouldOffer(info(1010), 1005, 0))
        assertFalse(UpdateParser.shouldOffer(info(1005), 1005, 0))
        assertFalse(UpdateParser.shouldOffer(info(1004), 1005, 0))
    }

    @Test fun offer_skippedVersionHidden_nextOneShown() {
        assertFalse(UpdateParser.shouldOffer(info(1010), 1005, 1010))
        assertTrue(UpdateParser.shouldOffer(info(1011), 1005, 1010))
    }

    @Test fun offer_requiredIgnoresSkip() {
        assertTrue(UpdateParser.shouldOffer(info(1010, required = true), 1005, 1010))
    }

    @Test fun size_text() {
        assertEquals("", UpdateParser.formatSize(0))
        assertEquals("1.5 MB", UpdateParser.formatSize(1_572_864))
        assertEquals("15 MB", UpdateParser.formatSize(15_728_640))
    }
}
