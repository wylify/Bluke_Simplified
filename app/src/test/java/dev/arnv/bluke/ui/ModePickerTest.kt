package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModePickerTest {
    private val slots = 4

    private fun target(dx: Float, dy: Float, wasLifted: Boolean = false) =
        resolveModePickerTarget(dx, dy, slots, wasLifted)

    @Test
    fun fingerOnTheButtonPointsAtNothing() {
        assertEquals(ModePickerTarget.None, target(0f, 0f))
        assertEquals(ModePickerTarget.None, target(15f, -15f))
    }

    @Test
    fun slidingSidewaysScrubsThroughTheModesWithoutLifting() {
        assertEquals(ModePickerTarget(0, lifted = false), target(-40f, 0f))
        assertEquals(ModePickerTarget(1, lifted = false), target(64f, 0f))
        assertEquals(ModePickerTarget(2, lifted = false), target(140f, 10f))
        assertEquals(ModePickerTarget(3, lifted = false), target(600f, 0f))
    }

    @Test
    fun slidingUpIntoThePillLiftsTheModeUnderTheFinger() {
        assertEquals(ModePickerTarget(0, lifted = true), target(0f, -70f))
        assertEquals(ModePickerTarget(2, lifted = true), target(130f, -60f))
    }

    @Test
    fun liftLineHasHysteresisSoItDoesNotFlicker() {
        assertEquals(ModePickerTarget(1, lifted = false), target(64f, -42f, wasLifted = false))
        assertEquals(ModePickerTarget(1, lifted = true), target(64f, -42f, wasLifted = true))
        assertEquals(ModePickerTarget(1, lifted = false), target(64f, -30f, wasLifted = true))
    }

    @Test
    fun pullingDownBelowTheButtonCancels() {
        assertEquals(ModePickerTarget.None, target(64f, 60f))
    }

    @Test
    fun releaseOutcomes() {
        assertEquals(ModePickerRelease.StayOpen, resolveModePickerRelease(ModePickerTarget.None, leftButton = false))
        assertEquals(ModePickerRelease.Cancel, resolveModePickerRelease(ModePickerTarget.None, leftButton = true))
        assertEquals(
            ModePickerRelease.Preselect(2),
            resolveModePickerRelease(ModePickerTarget(2, lifted = false), leftButton = true),
        )
        assertEquals(
            ModePickerRelease.Open(3),
            resolveModePickerRelease(ModePickerTarget(3, lifted = true), leftButton = true),
        )
    }

    @Test
    fun hapticsMarkEachNewModeAndTheLiftLine() {
        val none = ModePickerTarget.None
        assertEquals(ModePickerFeedback.Tick, modePickerTransitionFeedback(none, ModePickerTarget(0, false)))
        assertEquals(ModePickerFeedback.Tick, modePickerTransitionFeedback(ModePickerTarget(0, false), ModePickerTarget(1, false)))
        assertEquals(ModePickerFeedback.Lift, modePickerTransitionFeedback(ModePickerTarget(1, false), ModePickerTarget(1, true)))
        assertEquals(ModePickerFeedback.Drop, modePickerTransitionFeedback(ModePickerTarget(1, true), ModePickerTarget(1, false)))
        assertNull(modePickerTransitionFeedback(ModePickerTarget(1, false), none))
    }

    @Test
    fun stateTracksPreviewAndResetsOnDismiss() {
        val state = ModePickerState()
        assertNull(state.previewIndex)
        state.beginDrag(modePickerOrder(InputMode.GAMEPAD))
        state.target = ModePickerTarget(2, lifted = false)
        assertEquals(2, state.previewIndex)
        assertEquals(InputMode.KEYBOARD, state.previewMode)
        state.beginOpening(3)
        assertEquals(3, state.previewIndex)
        state.dismiss()
        assertNull(state.previewIndex)
        assertEquals(ModePickerTarget.None, state.target)
    }

    @Test
    fun currentModeSitsAboveTheButtonAndTheRestFollowInCycleOrder() {
        assertEquals(
            listOf(InputMode.GAMEPAD, InputMode.MEDIA_PRESENTATION, InputMode.KEYBOARD, InputMode.TOUCHPAD),
            modePickerOrder(InputMode.GAMEPAD),
        )
        assertEquals(InputMode.entries.toList(), modePickerOrder(InputMode.KEYBOARD))
    }

    @Test
    fun slowReleaseStaysWhereTheFingerIs() {
        assertEquals(64f to -10f, projectModePickerRelease(64f, -10f, vx = 200f, vy = 0f))
    }

    @Test
    fun quickFlickReachesTheFarthestIconFromAShortSwipe() {
        // A 50 dp swipe up and right, released while moving fast.
        val (px, py) = projectModePickerRelease(30f, -40f, vx = 1500f, vy = -400f)
        assertEquals(ModePickerTarget(3, lifted = true), resolveModePickerTarget(px, py, 4, wasLifted = false))
        val (sx, sy) = projectModePickerRelease(40f, 0f, vx = 1100f, vy = 0f)
        assertEquals(ModePickerTarget(3, lifted = false), resolveModePickerTarget(sx, sy, 4, wasLifted = false))
    }

    @Test
    fun wavesOpenFromWhereTheModeWasPickedAndCloseBackIntoIt() {
        val state = ModePickerState()
        state.beginDrag(modePickerOrder(InputMode.KEYBOARD))
        state.beginOpening(2)
        assertEquals(ModeWave(InputMode.GAMEPAD, ModeWaveOrigin.Slot(2), closing = false), state.wave)

        val launch = ModeWaveOrigin.Point(androidx.compose.ui.geometry.Offset(10f, 20f))
        state.launch(InputMode.TOUCHPAD, launch)
        assertEquals(ModeWave(InputMode.TOUCHPAD, launch, closing = false), state.wave)

        state.beginClosing(InputMode.TOUCHPAD, launch)
        assertEquals(true, state.wave?.closing)
        state.finishWave()
        assertNull(state.wave)
    }
}
