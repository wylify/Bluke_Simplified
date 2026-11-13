package dev.arnv.bluke.update

import android.content.SharedPreferences
import androidx.core.content.edit

const val UPDATE_CHECK_ENABLED_PREFERENCE = "update_check_enabled"
const val UPDATE_CHECK_INTERVAL_DAYS_PREFERENCE = "update_check_interval_days"
const val UPDATE_LAST_CHECKED_PREFERENCE = "update_last_checked"
const val UPDATE_SKIPPED_VERSION_PREFERENCE = "update_skipped_version"

/** Off by default: the app makes no network requests unless the user turns this on. */
var SharedPreferences.isUpdateCheckEnabled: Boolean
    get() = getBoolean(UPDATE_CHECK_ENABLED_PREFERENCE, false)
    set(value) = edit { putBoolean(UPDATE_CHECK_ENABLED_PREFERENCE, value) }

var SharedPreferences.updateCheckInterval: UpdateInterval
    get() = UpdateInterval.fromDays(getInt(UPDATE_CHECK_INTERVAL_DAYS_PREFERENCE, UpdateInterval.DEFAULT.days))
    set(value) = edit { putInt(UPDATE_CHECK_INTERVAL_DAYS_PREFERENCE, value.days) }

/** When the last check that reached GitHub happened, or 0 if never. */
val SharedPreferences.updateLastChecked: Long
    get() = getLong(UPDATE_LAST_CHECKED_PREFERENCE, 0L)

const val UPDATE_OPT_IN_ASKED_PREFERENCE = "update_opt_in_asked"

/**
 * True until the user has answered the first-open question. Someone who already set the switch
 * in About has made their choice, so they aren't asked.
 */
val SharedPreferences.shouldAskUpdateOptIn: Boolean
    get() = !getBoolean(UPDATE_OPT_IN_ASKED_PREFERENCE, false) && !contains(UPDATE_CHECK_ENABLED_PREFERENCE)

fun SharedPreferences.answerUpdateOptIn(enable: Boolean) = edit {
    putBoolean(UPDATE_OPT_IN_ASKED_PREFERENCE, true)
    putBoolean(UPDATE_CHECK_ENABLED_PREFERENCE, enable)
}

fun SharedPreferences.skipUpdateVersion(version: String) =
    edit { putString(UPDATE_SKIPPED_VERSION_PREFERENCE, displayVersion(version)) }

/**
 * True when a check is due. A clock that went backwards (last check in the future) counts as due,
 * so a wrong date can't block checks for a month.
 */
fun isUpdateCheckDue(nowMillis: Long, lastCheckedMillis: Long, interval: UpdateInterval): Boolean =
    lastCheckedMillis <= 0L || nowMillis < lastCheckedMillis || nowMillis - lastCheckedMillis >= interval.millis

sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    /** GitHub could not be reached or answered with something unreadable. */
    data object Failed : UpdateCheckResult
    /** Nothing was requested: checks are off or the interval hasn't passed. */
    data object NotDue : UpdateCheckResult
}

/** Runs only while the app is open; there is no background work. */
class UpdateChecker(
    private val prefs: SharedPreferences,
    private val currentVersion: String,
    private val source: ReleaseSource = GitHubReleaseClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The automatic check: respects the switch, the interval and a skipped version. */
    suspend fun checkIfDue(): UpdateCheckResult {
        if (!prefs.isUpdateCheckEnabled) return UpdateCheckResult.NotDue
        if (!isUpdateCheckDue(clock(), prefs.updateLastChecked, prefs.updateCheckInterval)) {
            return UpdateCheckResult.NotDue
        }
        return check(respectSkipped = true)
    }

    /** "Check now": asked for by the user, so it ignores the switch, interval and skipped version. */
    suspend fun checkNow(): UpdateCheckResult = check(respectSkipped = false)

    private suspend fun check(respectSkipped: Boolean): UpdateCheckResult {
        val release = source.fetchLatest() ?: return UpdateCheckResult.Failed
        // Only a successful answer counts; a failed one is retried the next time the app opens.
        prefs.edit { putLong(UPDATE_LAST_CHECKED_PREFERENCE, clock()) }
        if (!isNewerVersion(release.version, currentVersion)) return UpdateCheckResult.UpToDate
        val skipped = prefs.getString(UPDATE_SKIPPED_VERSION_PREFERENCE, null)
        if (respectSkipped && skipped == release.version) return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(release)
    }
}
