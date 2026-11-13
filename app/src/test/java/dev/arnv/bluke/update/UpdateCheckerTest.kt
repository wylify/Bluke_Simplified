package dev.arnv.bluke.update

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCheckerTest {
    private lateinit var prefs: SharedPreferences
    private var now = 1_000 * UpdateInterval.DAY_MILLIS
    private var fetches = 0
    private var latest: GitHubRelease? = release("1.2.0")

    private fun release(version: String, apk: String? = "https://example.invalid/a.apk") =
        GitHubRelease(version, "https://github.com/$UPDATE_REPOSITORY/releases/tag/v$version", apk, "notes")

    private fun checker(current: String = "1.1.5") = UpdateChecker(
        prefs = prefs,
        currentVersion = current,
        source = { fetches++; latest },
        clock = { now },
    )

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun offByDefaultAndMakesNoRequest() = runTest {
        assertFalse(prefs.isUpdateCheckEnabled)
        assertEquals(UpdateCheckResult.NotDue, checker().checkIfDue())
        assertEquals(0, fetches)
    }

    @Test
    fun optInIsAskedOnceAndAnswerSticks() {
        assertTrue(prefs.shouldAskUpdateOptIn)
        prefs.answerUpdateOptIn(false)
        assertFalse(prefs.shouldAskUpdateOptIn)
        assertFalse(prefs.isUpdateCheckEnabled)

        prefs.edit().clear().commit()
        prefs.answerUpdateOptIn(true)
        assertFalse(prefs.shouldAskUpdateOptIn)
        assertTrue(prefs.isUpdateCheckEnabled)
    }

    @Test
    fun optInIsNotAskedWhenSwitchWasAlreadySetInAbout() {
        prefs.isUpdateCheckEnabled = false
        assertFalse(prefs.shouldAskUpdateOptIn)
    }

    @Test
    fun offersNewerReleaseThenWaitsForInterval() = runTest {
        prefs.isUpdateCheckEnabled = true
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkIfDue())
        assertEquals(now, prefs.updateLastChecked)

        now += 6 * UpdateInterval.DAY_MILLIS
        assertEquals(UpdateCheckResult.NotDue, checker().checkIfDue())
        assertEquals(1, fetches)

        now += UpdateInterval.DAY_MILLIS
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkIfDue())
        assertEquals(2, fetches)
    }

    @Test
    fun failedCheckIsRetriedNextTime() = runTest {
        prefs.isUpdateCheckEnabled = true
        latest = null
        assertEquals(UpdateCheckResult.Failed, checker().checkIfDue())
        assertEquals(0L, prefs.updateLastChecked)
        latest = release("1.2.0")
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkIfDue())
    }

    @Test
    fun sameOrOlderVersionIsUpToDate() = runTest {
        prefs.isUpdateCheckEnabled = true
        latest = release("1.1.5")
        assertEquals(UpdateCheckResult.UpToDate, checker().checkIfDue())
    }

    @Test
    fun skippedVersionIsHiddenUntilANewerOneOrACheckNow() = runTest {
        prefs.isUpdateCheckEnabled = true
        prefs.skipUpdateVersion("v1.2.0")
        assertEquals(UpdateCheckResult.UpToDate, checker().checkIfDue())
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkNow())

        latest = release("1.3.0")
        now += 7 * UpdateInterval.DAY_MILLIS
        assertEquals(UpdateCheckResult.Available(release("1.3.0")), checker().checkIfDue())
    }

    @Test
    fun checkNowIgnoresSwitchAndInterval() = runTest {
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkNow())
        assertEquals(UpdateCheckResult.Available(release("1.2.0")), checker().checkNow())
        assertEquals(2, fetches)
    }

    @Test
    fun parsesGitHubResponseAndPicksTheApk() {
        val json = """
            {"tag_name":"v1.2.0","html_url":"https://github.com/$UPDATE_REPOSITORY/releases/tag/v1.2.0",
             "body":"### Added\n- **New:** thing [link](https://x)\n",
             "assets":[{"name":"notes.txt","browser_download_url":"https://e.invalid/notes.txt"},
                       {"name":"simply-bluke-1.2.0.apk","browser_download_url":"https://e.invalid/simply-bluke-1.2.0.apk"}]}
        """.trimIndent()
        val parsed = parseGitHubRelease(json)!!
        assertEquals("1.2.0", parsed.version)
        assertEquals("https://e.invalid/simply-bluke-1.2.0.apk", parsed.downloadUrl)
        assertEquals("Added\n• New: thing link", plainReleaseNotes(parsed.notes))
    }

    @Test
    fun releaseWithoutApkFallsBackToPageAndBadJsonIsNull() {
        val parsed = parseGitHubRelease(
            """{"tag_name":"v1.2.0","html_url":"https://github.com/r","body":null,"assets":[]}""",
        )!!
        assertNull(parsed.apkUrl)
        assertEquals("https://github.com/r", parsed.downloadUrl)
        assertEquals("", parsed.notes)
        assertNull(parseGitHubRelease("""{"message":"Not Found"}"""))
        assertNull(parseGitHubRelease("not json"))
    }
}
