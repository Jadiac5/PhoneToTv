package com.phonestream.app.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class VersionTest {
    @Test
    fun parsesPlainAndPrefixedVersions() {
        assertEquals(listOf(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(listOf(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), Version.parse(" V1.2.3 "))
        assertEquals(listOf(7), Version.parse("7"))
    }

    @Test
    fun suffixesAreIgnored() {
        assertEquals(listOf(1, 10, 2), Version.parse("1.10.2-beta1"))
        assertEquals(listOf(2, 0, 0), Version.parse("v2.0.0+build5"))
    }

    @Test
    fun nonsenseIsNotAVersion() {
        for (s in listOf("", "abc", "v", "1..2", "1.", ".1", "nightly", "1.12345678901")) {
            assertNull("'$s'", Version.parse(s))
        }
    }

    @Test
    fun normalizeDropsTheV() {
        assertEquals("1.2.0", Version.normalize("v1.2.0"))
        assertEquals("1.2", Version.normalize("1.2-rc1"))
        assertNull(Version.normalize("release"))
    }

    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(Version.isNewer("1.1.0", "1.0.0"))
        assertTrue(Version.isNewer("1.10.0", "1.9.0"))
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
        assertTrue(Version.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Version.isNewer("1.1.0.1", "1.1.0"))
    }

    @Test
    fun equalOrOlderIsNotNewer() {
        assertFalse(Version.isNewer("1.1.0", "1.1.0"))
        assertFalse(Version.isNewer("1.1", "1.1.0"))
        assertFalse(Version.isNewer("1.1.0", "1.1"))
        assertFalse(Version.isNewer("1.0.9", "1.1.0"))
        assertFalse(Version.isNewer("0.9", "1.0.0"))
    }

    @Test
    fun unreadableVersionsNeverTriggerAnUpdate() {
        assertFalse(Version.isNewer("junk", "1.0.0"))
        assertFalse(Version.isNewer("2.0.0", "junk"))
        assertFalse(Version.isNewer("", ""))
    }
}

class ReleaseParserTest {
    private val sha = "a".repeat(63) + "B"
    private val base = Updates.DOWNLOAD_PREFIX + "v1.2.0/"

    private fun asset(name: String, url: String = base + name, size: Long = 12_345_678L, digest: String? = "sha256:$sha") =
        buildString {
            append("""{"name":"$name","browser_download_url":"$url","size":$size""")
            if (digest != null) append(""","digest":"$digest"""")
            append("}")
        }

    private fun release(
        tag: String = "v1.2.0",
        assets: List<String> = listOf(asset("PhoneStream-1.2.0.apk")),
        extra: String = "",
        body: String = "  - faster\\n- fixes  \\n",
    ) = """{"tag_name":"$tag","html_url":"https://github.com/Jadiac5/PhoneToTv/releases/tag/$tag",
        "body":"$body","assets":[${assets.joinToString(",")}]$extra}"""

    @Test
    fun readsAnOrdinaryRelease() {
        val r = ReleaseParser.parse(release())!!
        assertEquals("1.2.0", r.version)
        assertEquals(base + "PhoneStream-1.2.0.apk", r.apkUrl)
        assertEquals(12_345_678L, r.size)
        assertEquals(("a".repeat(63) + "b"), r.sha256) // normalised to lower case
        assertEquals("- faster\n- fixes", r.notes)
        assertEquals("https://github.com/Jadiac5/PhoneToTv/releases/tag/v1.2.0", r.pageUrl)
    }

    @Test
    fun aMissingOrMalformedDigestJustMeansNoChecksum() {
        assertNull(ReleaseParser.parse(release(assets = listOf(asset("PhoneStream.apk", digest = null))))!!.sha256)
        assertNull(ReleaseParser.parse(release(assets = listOf(asset("PhoneStream.apk", digest = "sha256:abc"))))!!.sha256)
        assertNull(ReleaseParser.parse(release(assets = listOf(asset("PhoneStream.apk", digest = "md5:" + "a".repeat(64)))))!!.sha256)
        assertNull(ReleaseParser.parse(release(assets = listOf(asset("PhoneStream.apk", digest = "sha256:" + "g".repeat(64)))))!!.sha256)
    }

    @Test
    fun prefersTheAppApkOverOtherApks() {
        val r = ReleaseParser.parse(release(assets = listOf(asset("other-tool.apk"), asset("PhoneStream-1.2.0.apk"))))!!
        assertTrue(r.apkUrl.endsWith("PhoneStream-1.2.0.apk"))
        val only = ReleaseParser.parse(release(assets = listOf(asset("whatever.apk"))))!!
        assertTrue(only.apkUrl.endsWith("whatever.apk"))
    }

    @Test
    fun ignoresFilesThatAreNotApks() {
        assertNull(ReleaseParser.parse(release(assets = listOf(asset("PhoneStream-1.2.0.zip"), asset("notes.txt")))))
        assertNull(ReleaseParser.parse(release(assets = emptyList())))
    }

    @Test
    fun neverTrustsADownloadLinkOutsideOurOwnRepository() {
        val evil = asset("PhoneStream-1.2.0.apk", url = "https://evil.example/PhoneStream-1.2.0.apk")
        assertNull(ReleaseParser.parse(release(assets = listOf(evil))))
        val otherRepo = asset("PhoneStream-1.2.0.apk", url = "https://github.com/someone/else/releases/download/v1/PhoneStream-1.2.0.apk")
        assertNull(ReleaseParser.parse(release(assets = listOf(otherRepo))))
        // a good asset next to a bad one is still found
        val r = ReleaseParser.parse(release(assets = listOf(evil, asset("PhoneStream-1.2.0.apk"))))
        assertNotNull(r)
        assertTrue(r!!.apkUrl.startsWith(Updates.DOWNLOAD_PREFIX))
    }

    @Test
    fun draftsAndPrereleasesAreNotOffered() {
        assertNull(ReleaseParser.parse(release(extra = ""","draft":true""")))
        assertNull(ReleaseParser.parse(release(extra = ""","prerelease":true""")))
    }

    @Test
    fun tagsWithoutAVersionAreNotOffered() {
        assertNull(ReleaseParser.parse(release(tag = "nightly")))
    }

    @Test
    fun garbageIsNotARelease() {
        assertNull(ReleaseParser.parse(""))
        assertNull(ReleaseParser.parse("<html>rate limited</html>"))
        assertNull(ReleaseParser.parse("{}"))
        assertNull(ReleaseParser.parse("""{"message":"Not Found","status":"404"}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":"v1.0.0"}"""))
    }

    @Test
    fun theRepositoryConstantsAgree() {
        assertTrue(Updates.LATEST_URL.startsWith("https://api.github.com/repos/${Updates.REPO}/"))
        assertTrue(Updates.DOWNLOAD_PREFIX.startsWith("https://github.com/${Updates.REPO}/"))
    }
}

class UpdateUiTest {
    private lateinit var oldLocale: Locale

    @Before
    fun fixLocale() {
        oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // "%.1f" must not turn into "5,0" in the expected strings
    }

    @After
    fun restoreLocale() = Locale.setDefault(oldLocale)

    private val info = ReleaseInfo("1.2.0", "u", 5_242_880L, null, "notes", "p")
    private fun s(phase: UpdatePhase, info: ReleaseInfo? = null, progress: Int = 0, message: String = "") =
        UpdateState(phase, info, progress, message)

    @Test
    fun oneButtonChecksFirstThenUpdates() {
        assertEquals(UpdateAction.CHECK, UpdateUi.action(s(UpdatePhase.IDLE)))
        assertEquals("Check for updates", UpdateUi.buttonLabel(s(UpdatePhase.IDLE)))

        assertEquals(UpdateAction.NONE, UpdateUi.action(s(UpdatePhase.CHECKING)))
        assertEquals("Checking…", UpdateUi.buttonLabel(s(UpdatePhase.CHECKING)))

        assertEquals(UpdateAction.CHECK, UpdateUi.action(s(UpdatePhase.UP_TO_DATE)))
        assertEquals("Check again", UpdateUi.buttonLabel(s(UpdatePhase.UP_TO_DATE)))

        assertEquals(UpdateAction.UPDATE, UpdateUi.action(s(UpdatePhase.AVAILABLE, info)))
        assertEquals("Update to v1.2.0", UpdateUi.buttonLabel(s(UpdatePhase.AVAILABLE, info)))
    }

    @Test
    fun theButtonIsInertWhileWorking() {
        assertEquals(UpdateAction.NONE, UpdateUi.action(s(UpdatePhase.DOWNLOADING, info, 40)))
        assertEquals("Downloading 40%", UpdateUi.buttonLabel(s(UpdatePhase.DOWNLOADING, info, 40)))
        assertEquals(UpdateAction.NONE, UpdateUi.action(s(UpdatePhase.INSTALLING, info)))
        assertEquals("Installing…", UpdateUi.buttonLabel(s(UpdatePhase.INSTALLING, info)))
    }

    @Test
    fun anErrorRetriesTheRightThing() {
        val failedDownload = s(UpdatePhase.ERROR, info, message = "Download failed")
        assertEquals(UpdateAction.UPDATE, UpdateUi.action(failedDownload))
        assertEquals("Try again", UpdateUi.buttonLabel(failedDownload))

        val failedCheck = s(UpdatePhase.ERROR, message = "No internet")
        assertEquals(UpdateAction.CHECK, UpdateUi.action(failedCheck))
        assertEquals("Check again", UpdateUi.buttonLabel(failedCheck))
    }

    @Test
    fun theStartScreenButtonShowsOnlyWhenThereIsSomethingToDo() {
        assertNull(UpdateUi.pill(s(UpdatePhase.IDLE)))
        assertNull(UpdateUi.pill(s(UpdatePhase.CHECKING)))
        assertNull(UpdateUi.pill(s(UpdatePhase.UP_TO_DATE)))
        assertNull("a failed check is not worth a button", UpdateUi.pill(s(UpdatePhase.ERROR, message = "No internet")))

        assertEquals("Update available · v1.2.0", UpdateUi.pill(s(UpdatePhase.AVAILABLE, info)))
        assertEquals("Downloading update… 70%", UpdateUi.pill(s(UpdatePhase.DOWNLOADING, info, 70)))
        assertEquals("Installing update…", UpdateUi.pill(s(UpdatePhase.INSTALLING, info)))
        assertEquals("Update failed · tap to retry", UpdateUi.pill(s(UpdatePhase.ERROR, info, message = "x")))
    }

    @Test
    fun statusTextSaysWhatIsGoingOn() {
        assertEquals("", UpdateUi.status(s(UpdatePhase.IDLE)))
        assertEquals("Looking for a new version…", UpdateUi.status(s(UpdatePhase.CHECKING)))
        assertEquals("You have the latest version.", UpdateUi.status(s(UpdatePhase.UP_TO_DATE, message = "You have the latest version.")))
        assertEquals("Version 1.2.0 is available (5.0 MB)", UpdateUi.status(s(UpdatePhase.AVAILABLE, info)))
        assertEquals(
            "Version 1.2.0 is available (5.0 MB)\nAllow installs, then tap again",
            UpdateUi.status(s(UpdatePhase.AVAILABLE, info, message = "Allow installs, then tap again"))
        )
        assertEquals("Version 1.2.0 is available", UpdateUi.status(s(UpdatePhase.AVAILABLE, info.copy(size = 0))))
        assertEquals("Download failed", UpdateUi.status(s(UpdatePhase.ERROR, message = "Download failed")))
        assertEquals("Installing…", UpdateUi.status(s(UpdatePhase.INSTALLING, info)))
    }

    @Test
    fun longReleaseNotesAreShortened() {
        assertEquals("short", UpdateUi.shortNotes("  short \n"))
        val long = "word ".repeat(400)
        val cut = UpdateUi.shortNotes(long, 100)
        assertTrue(cut.length <= 101)
        assertTrue(cut.endsWith("…"))
    }
}
