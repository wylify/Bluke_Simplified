package dev.arnv.bluke.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun parsesTagsWithAndWithoutPrefix() {
        assertEquals(listOf(1, 2, 0), parseVersion("v1.2.0"))
        assertEquals(listOf(1, 1, 5), parseVersion("1.1.5"))
        assertEquals(listOf(2, 0), parseVersion("2.0-beta1"))
        assertNull(parseVersion("latest"))
        assertNull(parseVersion(""))
        assertNull(parseVersion("1..2"))
    }

    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(isNewerVersion("v1.2.0", "1.1.5"))
        assertTrue(isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("1.1.5.1", "1.1.5"))
        assertFalse(isNewerVersion("1.1.5", "1.1.5"))
        assertFalse(isNewerVersion("v1.1.5", "1.1.5.0"))
        assertFalse(isNewerVersion("1.1.4", "1.1.5"))
    }

    @Test
    fun unreadableVersionsAreNeverNewer() {
        assertFalse(isNewerVersion("nightly", "1.1.5"))
        assertFalse(isNewerVersion("2.0.0", "dev"))
    }

    @Test
    fun intervalDefaultsToWeeklyAndClampsToDailyThroughMonthly() {
        assertEquals(UpdateInterval.WEEKLY, UpdateInterval.DEFAULT)
        assertEquals(UpdateInterval.DAILY, UpdateInterval.fromDays(0))
        assertEquals(UpdateInterval.DAILY, UpdateInterval.fromDays(-5))
        assertEquals(UpdateInterval.MONTHLY, UpdateInterval.fromDays(365))
        assertEquals(UpdateInterval.EVERY_2_WEEKS, UpdateInterval.fromDays(14))
        assertEquals(UpdateInterval.WEEKLY, UpdateInterval.fromDays(8))
    }

    @Test
    fun checkIsDueAfterIntervalOrWhenClockWentBack() {
        val day = UpdateInterval.DAY_MILLIS
        val last = 100 * day
        assertTrue(isUpdateCheckDue(last, 0L, UpdateInterval.WEEKLY))
        assertFalse(isUpdateCheckDue(last + 6 * day, last, UpdateInterval.WEEKLY))
        assertTrue(isUpdateCheckDue(last + 7 * day, last, UpdateInterval.WEEKLY))
        assertTrue(isUpdateCheckDue(last + day, last, UpdateInterval.DAILY))
        assertTrue(isUpdateCheckDue(last - day, last, UpdateInterval.MONTHLY))
    }
}
