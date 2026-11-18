package dev.arnv.bluke.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * "Scrub & Lift" input mode picker for the home screen's mode button.
 *
 * Hold the button and a pill of all input modes grows out of it, the current mode right above
 * the button and the others following it in cycle order.
 * Slide sideways to scrub through the modes; letting go there only preselects the mode.
 * Slide up into the pill to lift the highlighted mode; letting go then opens it.
 * Letting go back on the button, or after pulling down below it, cancels.
 * A quick flick is projected forward, so a short throw towards an icon reaches it.
 * Letting go without moving at all keeps the pill open as an ordinary tappable menu.
 */

/** Gesture geometry in dp, relative to the centre of the mode button. Up is negative y. */
/** Material 3's emphasized easing: a quick start that settles gently. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Its accelerating counterpart, for things leaving: a gentle start that speeds away. */
private val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

internal object ModePickerMetrics {
    const val BUTTON_RADIUS = 32f
    const val SLOT_SPACING = 64f
    const val SLOT_SIZE = 52f
    const val PILL_GAP = 16f
    const val PILL_HEIGHT = 64f

    /** Letting go inside this radius of the button's centre cancels. */
    const val CANCEL_RADIUS = 26f

    /** Pulling this far below the button's centre cancels too. */
    const val CANCEL_BELOW = 44f

    /** The finger lifts a mode once it is this far above the button's centre (inside the pill)... */
    const val LIFT_ENTER = 48f

    /** ...and drops it again only below this line, so the boundary doesn't flicker. */
    const val LIFT_EXIT = 38f

    /** Releases faster than this (dp/s) count as a flick and are projected forward... */
    const val FLICK_SPEED = 650f

    /** ...by this many seconds of their velocity. */
    const val FLICK_PROJECTION = 0.12f

    const val SLOT_CENTER_Y = -(BUTTON_RADIUS + PILL_GAP + PILL_HEIGHT / 2f)
    const val LIFT_RISE = 12f
}

internal data class ModePickerTarget(val index: Int?, val lifted: Boolean) {
    companion object {
        val None = ModePickerTarget(index = null, lifted = false)
    }
}

internal sealed interface ModePickerRelease {
    data object Cancel : ModePickerRelease
    data object StayOpen : ModePickerRelease
    data class Preselect(val index: Int) : ModePickerRelease
    data class Open(val index: Int) : ModePickerRelease
}

/** Which mode the finger at ([dx], [dy]) dp from the button's centre points at. */
internal fun resolveModePickerTarget(dx: Float, dy: Float, slotCount: Int, wasLifted: Boolean): ModePickerTarget {
    if (slotCount <= 0) return ModePickerTarget.None
    if (hypot(dx, dy) < ModePickerMetrics.CANCEL_RADIUS) return ModePickerTarget.None
    if (dy > ModePickerMetrics.CANCEL_BELOW) return ModePickerTarget.None
    val index = (dx / ModePickerMetrics.SLOT_SPACING).roundToInt().coerceIn(0, slotCount - 1)
    val liftLine = if (wasLifted) ModePickerMetrics.LIFT_EXIT else ModePickerMetrics.LIFT_ENTER
    return ModePickerTarget(index, lifted = dy < -liftLine)
}

/** What letting go does, given where the finger points and whether it ever left the button. */
internal fun resolveModePickerRelease(target: ModePickerTarget, leftButton: Boolean): ModePickerRelease {
    val index = target.index
    return when {
        !leftButton -> ModePickerRelease.StayOpen
        index == null -> ModePickerRelease.Cancel
        target.lifted -> ModePickerRelease.Open(index)
        else -> ModePickerRelease.Preselect(index)
    }
}

/** The picker's slots: [current] first (above the button), then the others in cycle order. */
internal fun modePickerOrder(current: InputMode, all: List<InputMode> = InputMode.entries): List<InputMode> {
    val start = all.indexOf(current).coerceAtLeast(0)
    return List(all.size) { all[(start + it) % all.size] }
}

/**
 * Where a release at ([dx], [dy]) dp moving at ([vx], [vy]) dp/s points. A slow release stays
 * where it is; a flick is carried forward so the finger needn't travel all the way.
 */
internal fun projectModePickerRelease(dx: Float, dy: Float, vx: Float, vy: Float): Pair<Float, Float> {
    if (hypot(vx, vy) < ModePickerMetrics.FLICK_SPEED) return dx to dy
    return (dx + vx * ModePickerMetrics.FLICK_PROJECTION) to (dy + vy * ModePickerMetrics.FLICK_PROJECTION)
}

internal val InputMode.icon: ImageVector
    get() = when (this) {
        InputMode.KEYBOARD -> Icons.Default.Keyboard
        InputMode.TOUCHPAD -> Icons.Default.Mouse
        InputMode.GAMEPAD -> Icons.Default.SportsEsports
        InputMode.MEDIA_PRESENTATION -> Icons.Default.SmartDisplay
    }

/** Where a mode's opening wave starts, and where its closing wave collapses back to. */
internal sealed interface ModeWaveOrigin {
    /** A lifted icon in the picker. */
    data class Slot(val index: Int) : ModeWaveOrigin

    /** The round mode button. */
    data object ModeButton : ModeWaveOrigin

    /** Any other point, such as the centre of the Launch button. */
    data class Point(val inRoot: Offset) : ModeWaveOrigin
}

internal data class ModeWave(val mode: InputMode, val origin: ModeWaveOrigin, val closing: Boolean)

@Stable
internal class ModePickerState {
    var visible by mutableStateOf(false)
        private set
    var sticky by mutableStateOf(false)
        private set
    var pressed by mutableStateOf(false)
        internal set
    /** The modes in slot order, fixed while the picker is open. */
    var modes by mutableStateOf(InputMode.entries.toList())
        private set
    var target by mutableStateOf(ModePickerTarget.None)
        internal set
    var leftButton by mutableStateOf(false)
        internal set
    var anchorInRoot by mutableStateOf(Offset.Unspecified)
        internal set
    var openingIndex by mutableStateOf<Int?>(null)
        private set

    /** The opening or closing wave being played, if any. */
    var wave by mutableStateOf<ModeWave?>(null)
        private set

    /** The mode the finger is on right now, for previewing it on the button and Launch button. */
    val previewIndex: Int?
        get() = if (visible) openingIndex ?: target.index else null

    val previewMode: InputMode?
        get() = previewIndex?.let(modes::getOrNull)

    internal fun beginDrag(order: List<InputMode>) {
        modes = order
        visible = true
        sticky = false
        target = ModePickerTarget.None
        leftButton = false
        openingIndex = null
    }

    /** Opens the picker as a tappable menu, either fresh or by keeping an open picker open. */
    fun showMenu(order: List<InputMode> = modes) {
        if (!visible) beginDrag(order)
        target = ModePickerTarget.None
        sticky = true
    }

    fun beginOpening(index: Int) {
        target = ModePickerTarget(index, lifted = true)
        openingIndex = index
        wave = ModeWave(modes[index], ModeWaveOrigin.Slot(index), closing = false)
    }

    /** Opens [mode] with a wave from [origin], without the picker. */
    fun launch(mode: InputMode, origin: ModeWaveOrigin) {
        wave = ModeWave(mode, origin, closing = false)
    }

    /** Plays the wave in reverse: the closed [mode] collapses back into [origin]. */
    fun beginClosing(mode: InputMode, origin: ModeWaveOrigin) {
        wave = ModeWave(mode, origin, closing = true)
    }

    internal fun finishWave() {
        wave = null
    }

    fun dismiss() {
        visible = false
        sticky = false
        target = ModePickerTarget.None
        leftButton = false
        openingIndex = null
    }
}

internal enum class ModePickerFeedback { Shown, Tick, Lift, Drop, Confirm }

internal fun View.modePickerHaptic(feedback: ModePickerFeedback) {
    val constant = when (feedback) {
        ModePickerFeedback.Shown -> HapticFeedbackConstants.LONG_PRESS
        ModePickerFeedback.Tick -> HapticFeedbackConstants.CLOCK_TICK
        ModePickerFeedback.Lift -> if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        ModePickerFeedback.Drop -> if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        ModePickerFeedback.Confirm -> if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
    }
    performHapticFeedback(constant)
}

/** Haptic for moving from [from] to [to], or null when nothing changed that the finger should feel. */
internal fun modePickerTransitionFeedback(from: ModePickerTarget, to: ModePickerTarget): ModePickerFeedback? = when {
    to.index != null && to.lifted && !from.lifted -> ModePickerFeedback.Lift
    to.index != null && from.lifted && !to.lifted -> ModePickerFeedback.Drop
    to.index != null && to.index != from.index -> ModePickerFeedback.Tick
    else -> null
}

/**
 * Turns the mode button into the picker's trigger: a tap calls [onTap], a long press opens the
 * picker and the rest of the same touch drives it.
 */
internal fun Modifier.modePickerTrigger(
    state: ModePickerState,
    order: () -> List<InputMode>,
    onTap: () -> Unit,
    onShown: () -> Unit,
    onTargetChanged: (from: ModePickerTarget, to: ModePickerTarget) -> Unit,
    onRelease: (ModePickerRelease) -> Unit,
): Modifier = this
    .onGloballyPositioned { state.anchorInRoot = it.boundsInRoot().center }
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (state.visible) return@awaitEachGesture
            state.pressed = true
            try {
                val longPress = awaitLongPressOrCancellation(down.id)
                if (longPress == null) {
                    if (currentEvent.changes.any { it.id == down.id && it.changedToUp() }) onTap()
                    return@awaitEachGesture
                }
                longPress.consume()
                state.beginDrag(order())
                onShown()
                val slotCount = state.modes.size
                val center = Offset(size.width / 2f, size.height / 2f)
                val velocity = VelocityTracker()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        state.dismiss()
                        break
                    }
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val offset = change.position - center
                    val dx = offset.x.toDp().value
                    val dy = offset.y.toDp().value
                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        val v = velocity.calculateVelocity()
                        val (px, py) = projectModePickerRelease(dx, dy, v.x.toDp().value, v.y.toDp().value)
                        if (px != dx || py != dy) {
                            if (hypot(px, py) >= ModePickerMetrics.CANCEL_RADIUS) state.leftButton = true
                            state.target = resolveModePickerTarget(px, py, slotCount, state.target.lifted)
                        }
                        onRelease(resolveModePickerRelease(state.target, state.leftButton))
                        break
                    }
                    change.consume()
                    if (hypot(dx, dy) >= ModePickerMetrics.CANCEL_RADIUS) state.leftButton = true
                    val next = resolveModePickerTarget(dx, dy, slotCount, state.target.lifted)
                    if (next != state.target) {
                        val previous = state.target
                        state.target = next
                        onTargetChanged(previous, next)
                    }
                }
            } finally {
                state.pressed = false
                // A touch that was taken away mid-drag must not leave a half-open picker behind.
                if (state.visible && !state.sticky && state.openingIndex == null) state.dismiss()
            }
        }
    }

/**
 * Draws the picker over the whole screen. It must sit above the mode button in the same window,
 * and it covers the button with its own copy so the drop of liquid can grow out of it.
 */
@Composable
internal fun ModePickerOverlay(
    state: ModePickerState,
    currentMode: InputMode,
    onDismiss: () -> Unit,
    onMenuOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = state.modes
    // Slot 0 holds the current mode, so the highlight rests above the button.
    var lastIndex by remember { mutableIntStateOf(0) }
    val reduceMotion = rememberReduceMotion()
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val unit = with(density) { 1.dp.toPx() }

    val appear = remember { Animatable(0f) }
    val iconAppear = remember(modes.size) { List(modes.size) { Animatable(0f) } }
    LaunchedEffect(state.visible) {
        if (state.visible) {
            lastIndex = 0
            if (reduceMotion) {
                appear.snapTo(1f)
                iconAppear.forEach { it.snapTo(1f) }
            } else {
                appear.snapTo(0f)
                iconAppear.forEach { it.snapTo(0f) }
                launch { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                iconAppear.forEachIndexed { i, anim ->
                    launch {
                        delay(50L + 35L * i)
                        anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
                    }
                }
            }
        } else if (reduceMotion) {
            appear.snapTo(0f)
            iconAppear.forEach { it.snapTo(0f) }
        } else {
            iconAppear.forEach { launch { it.animateTo(0f, tween(110)) } }
            appear.animateTo(0f, tween(150, easing = FastOutLinearInEasing))
        }
    }

    BackHandler(enabled = state.visible) { onDismiss() }

    val target = state.target
    val shownIndex = target.index ?: lastIndex
    SideEffect { target.index?.let { lastIndex = it } }

    val blobTargetX = shownIndex * ModePickerMetrics.SLOT_SPACING * unit
    val blobX by animateFloatAsState(
        targetValue = blobTargetX,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "modePickerBlobX",
    )
    val blobAlpha by animateFloatAsState(
        targetValue = if (target.index != null) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(120),
        label = "modePickerBlobAlpha",
    )
    val lift by animateFloatAsState(
        targetValue = if (target.index != null && target.lifted) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "modePickerLift",
    )
    var origin by remember { mutableStateOf(Offset.Zero) }

    if (!state.visible && appear.value == 0f) return
    if (!state.anchorInRoot.isSpecified) return

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val anchor = state.anchorInRoot - origin
        val m = ModePickerMetrics
        val spanDp = (modes.size - 1) * m.SLOT_SPACING
        val buttonTop = anchor.y - m.BUTTON_RADIUS * unit
        val pillBottom = buttonTop - m.PILL_GAP * unit
        val pillTop = pillBottom - m.PILL_HEIGHT * unit
        val slotY = anchor.y + m.SLOT_CENTER_Y * unit
        val pivot = Offset(anchor.x, buttonTop)
        fun grown(point: Offset, p: Float) = pivot + (point - pivot) * p

        // Scrim; in menu mode a tap outside the pill closes it.
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (state.sticky) {
                        Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    } else {
                        Modifier
                    }
                )
        )

        Canvas(Modifier.fillMaxSize()) {
            val p = appear.value.coerceAtLeast(0f)
            drawRect(colors.scrim.copy(alpha = 0.32f * p.coerceAtMost(1f)))
            drawCircle(colors.secondaryContainer, m.BUTTON_RADIUS * unit, anchor)
            scale(p, p, pivot) {
                drawRoundRect(
                    color = colors.secondaryContainer,
                    topLeft = Offset(anchor.x - m.SLOT_SPACING / 2f * unit, pillTop),
                    size = Size((spanDp + m.SLOT_SPACING) * unit, m.PILL_HEIGHT * unit),
                    cornerRadius = CornerRadius(m.PILL_HEIGHT / 2f * unit),
                )
                // The highlight lags behind the finger and stretches while it catches up.
                val stretchWidth = (abs(blobX - blobTargetX) * 0.5f).coerceAtMost(32f * unit)
                val blobHeight = m.SLOT_SIZE * unit
                val blobWidth = blobHeight + stretchWidth
                val blobCenter = Offset(anchor.x + blobX, slotY - lift * m.LIFT_RISE * unit)
                drawRoundRect(
                    color = lerp(colors.onSecondaryContainer.copy(alpha = 0.12f), colors.primary, lift),
                    topLeft = Offset(blobCenter.x - blobWidth / 2f, blobCenter.y - blobHeight / 2f),
                    size = Size(blobWidth, blobHeight),
                    cornerRadius = CornerRadius(blobHeight / 2f),
                    alpha = blobAlpha,
                )
            }
        }

        // The button's own icon previews the mode under the finger.
        val previewMode = state.previewIndex?.let(modes::getOrNull) ?: currentMode
        Icon(
            imageVector = previewMode.icon,
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier.placeAt({ anchor }).size(26.dp),
        )

        modes.forEachIndexed { index, mode ->
            val selected = target.index == index
            val iconP = iconAppear[index].value
            val itemLift = if (selected) lift else 0f
            Box(
                modifier = Modifier
                    .placeAt({
                        grown(Offset(anchor.x + index * m.SLOT_SPACING * unit, slotY), appear.value) +
                            Offset(0f, -itemLift * m.LIFT_RISE * unit)
                    })
                    .size(m.SLOT_SIZE.dp)
                    .clip(CircleShape)
                    .then(
                        if (state.sticky) {
                            Modifier.clickable(onClickLabel = "Open ${mode.displayName}") { onMenuOpen(index) }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = mode.icon,
                    contentDescription = if (state.sticky) mode.displayName else null,
                    tint = lerp(colors.onSecondaryContainer, colors.onPrimary, itemLift),
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer {
                            val s = iconP * (if (selected) 1.1f + 0.12f * lift else 1f)
                            scaleX = s
                            scaleY = s
                            alpha = iconP.coerceIn(0f, 1f)
                        },
                )
            }
        }

        // A small caption above the pill says what letting go will do.
        val selectedMode = target.index?.let(modes::getOrNull)
        val title = selectedMode?.displayName ?: "Input modes"
        val hint = when {
            state.sticky -> "Tap a mode to open it"
            selectedMode == null -> "Slide to choose · up to open"
            target.lifted -> "Release to open"
            else -> "Release to select"
        }
        val edgeMargin = (12f * unit).roundToInt()
        Surface(
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .placeAt(
                    position = {
                        val x = if (target.index != null) anchor.x + blobX else anchor.x + spanDp / 2f * unit
                        Offset(x, pillTop - (10f + lift * m.LIFT_RISE) * unit)
                    },
                    alignBottom = true,
                    edgeMargin = edgeMargin,
                )
                .graphicsLayer {
                    val p = appear.value.coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * 8f * unit
                },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inverseOnSurface.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Plays [ModePickerState.wave]. Opening floods the screen from the origin with the session's own
 * background ([revealColor]) and then calls [onCovered]; the fill stays up while the session fades
 * in over it, and goes away with this screen. Closing starts fully covered, so the home screen
 * never shows before the wave, and collapses back into the origin.
 */
@Composable
internal fun ModeWaveOverlay(
    state: ModePickerState,
    revealColor: (InputMode) -> Color,
    onCovered: (InputMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val wave = state.wave
    val progress = remember { Animatable(if (wave?.closing == true && !reduceMotion) 1f else 0f) }
    val currentOnCovered by rememberUpdatedState(onCovered)
    LaunchedEffect(wave) {
        when {
            wave == null -> progress.snapTo(0f)
            !wave.closing -> {
                progress.snapTo(0f)
                if (!reduceMotion) progress.animateTo(1f, tween(380, easing = EmphasizedEasing))
                currentOnCovered(wave.mode)
            }
            else -> {
                if (!reduceMotion) {
                    progress.snapTo(1f)
                    // Let the session fade out over the fill first.
                    delay(140)
                    progress.animateTo(0f, tween(420, easing = EmphasizedAccelerateEasing))
                }
                progress.snapTo(0f)
                state.finishWave()
            }
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val unit = with(LocalDensity.current) { 1.dp.toPx() }

    if (wave == null) return

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val m = ModePickerMetrics
        val anchor = state.anchorInRoot
        val from: Offset? = when (val o = wave.origin) {
            is ModeWaveOrigin.Slot -> if (anchor.isSpecified) {
                anchor - origin + Offset(o.index * m.SLOT_SPACING * unit, (m.SLOT_CENTER_Y - m.LIFT_RISE) * unit)
            } else {
                null
            }
            ModeWaveOrigin.ModeButton -> if (anchor.isSpecified) anchor - origin else null
            is ModeWaveOrigin.Point -> if (o.inRoot.isSpecified) o.inRoot - origin else null
        }
        val fill = revealColor(wave.mode)
        Canvas(Modifier.fillMaxSize()) {
            val e = progress.value
            if (e <= 0f) return@Canvas
            // Until the origin has been laid out, just cover everything.
            if (from == null) {
                drawRect(fill)
                return@Canvas
            }
            val farthest = maxOf(
                hypot(from.x, from.y),
                hypot(size.width - from.x, from.y),
                hypot(from.x, size.height - from.y),
                hypot(size.width - from.x, size.height - from.y),
            )
            val start = m.BUTTON_RADIUS * unit
            val radius = start + (farthest - start) * e
            drawCircle(fill, radius, from)
            // A soft accent glow at the centre and a rim on the wave's edge, strongest near the origin.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.28f * (1f - e)), Color.Transparent),
                    center = from,
                    radius = radius,
                ),
                radius = radius,
                center = from,
            )
            drawCircle(
                color = colors.primary.copy(alpha = 0.6f * (1f - e)),
                radius = radius,
                center = from,
                style = Stroke(width = (2f + 6f * (1f - e)) * unit),
            )
        }
        // The mode's icon rides the wave out, and comes back with it when closing.
        if (from != null) {
            Icon(
                imageVector = wave.mode.icon,
                contentDescription = null,
                tint = if (wave.closing && wave.origin == ModeWaveOrigin.ModeButton) {
                    lerp(colors.onSecondaryContainer, colors.primary, (progress.value * 4f).coerceAtMost(1f))
                } else {
                    colors.primary
                },
                modifier = Modifier
                    .placeAt({ from })
                    .size(26.dp)
                    .graphicsLayer {
                        val e = progress.value
                        val s = 1f + e
                        scaleX = s
                        scaleY = s
                        alpha = if (e <= 0f) 0f else (1f - e * 1.25f).coerceIn(0f, 1f)
                    },
            )
        }
    }
}

/**
 * Takes the whole parent and places the content centred on [position] (or with its bottom edge
 * there when [alignBottom]), kept [edgeMargin] px inside the parent's sides.
 */
private fun Modifier.placeAt(
    position: () -> Offset,
    alignBottom: Boolean = false,
    edgeMargin: Int = 0,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val at = position()
        val maxX = (constraints.maxWidth - placeable.width - edgeMargin).coerceAtLeast(edgeMargin)
        val x = (at.x - placeable.width / 2f).roundToInt().coerceIn(edgeMargin, maxX)
        val y = if (alignBottom) at.y - placeable.height else at.y - placeable.height / 2f
        placeable.place(x, y.roundToInt())
    }
}
