package cc.machado.audioblackbox.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * An avionics chassis card matching `docs/design/model.html`'s `.avionics-card`:
 * - Dark cockpit panel fill ([CockpitPanel])
 * - Translucent border ([CockpitBorderStrong])
 * - 14dp rounded corners ([CARD_SHAPE])
 * - Subtle corner rivet screws (`＋`) in slate on top corners
 */
@Composable
fun AvionicsCard(
    modifier: Modifier = Modifier,
    shape: Shape = CARD_SHAPE,
    showRivets: Boolean = true,
    innerPadding: Dp = CARD_INNER_PADDING,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = CockpitPanel,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (showRivets) {
                // Top-left rivet screw
                Text(
                    text = "＋",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF475569),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 6.dp),
                )
                // Top-right rivet screw
                Text(
                    text = "＋",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF475569),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 6.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                content = content,
            )
        }
    }
}

/**
 * Top data plate header bar matching `docs/design/model.html`'s `.card-label-bar`:
 * Monospace uppercase label with optional trailing status tag.
 */
@Composable
fun AvionicsCardHeaderBar(
    label: String,
    modifier: Modifier = Modifier,
    tag: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
            color = TextDim,
        )
        tag?.invoke()
    }
}

/**
 * Stencil tag / badge matching `docs/design/model.html`'s `.top-tag`.
 */
@Composable
fun AvionicsTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FlightOrange,
    containerColor: Color = FlightOrangeContainer,
    borderColor: Color = color.copy(alpha = 0.35f),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(RADIUS_RIVET),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.04.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Design proposal for issue #335: an aviation-panel button primitive, sized and lit like a
 * physical rocker/press switch rather than a `Material3` `Button` -- a rectangular bezel, a status
 * LED, an all-caps monospace label, and an optional one-line caption underneath it for the state
 * detail a caption-less icon button would otherwise lose. It is meant to sit in a
 * [AvionicsPanelButtonRow] of two, inside [EngineCard]'s chassis, replacing the two full-sentence
 * `Button`s that used to live in `SaveSection`/`ForwardRecordingSection` (see the PR for #335 for
 * the full writeup of what moved and why).
 *
 * States, all of which the two real actions need (issue #335's "cover every state" requirement):
 * - **Enabled**, [ledColor] lit ([FlightOrange] by default -- the brand CTA colour, matching
 *   [primaryCtaButtonColors]).
 * - **Disabled with a reason**: pass `enabled = false` and put the reason in [caption] (e.g. "NO
 *   AUDIO YET" for Save with an empty buffer). The LED dims to [TextDim] and the whole control's
 *   alpha drops, same visual language `Button` already uses for its disabled state.
 * - **In progress**: pass `inProgress = true`. The LED is replaced by a small spinner and the
 *   caption should describe the operation ("WRITING TO DISK…"); [onClick] is still wired by the
 *   caller but the control also renders disabled-styled so a second tap mid-export cannot double
 *   fire (mirrors the existing 500 ms debounce in `SaveSection`, which callers keep doing
 *   independently -- this primitive does not replace that guard).
 * - **Toggled / "stop" state**: same primitive, different [label]/[caption]/[ledColor] (e.g.
 *   [WarningRed] or [CautionAmber] for a live "STOP" control) -- there is deliberately no separate
 *   "stop variant" composable, because `ForwardRecordingSection` already proved the start/stop pair
 *   is one control whose text and color change, not two controls.
 *
 * Content description: pass the *existing* accessibility strings
 * (`dashboard_save_button_cd_full`/`_partial`, etc.) as [contentDescription] unchanged -- this
 * primitive does not shorten or replace them, only the visible [label] shortens for panel framing.
 *
 * Touch target: the control enforces a 48dp minimum height ([PANEL_BUTTON_MIN_HEIGHT]) regardless
 * of how little [label] + [caption] need, per the platform accessibility minimum -- previously this
 * came for free from `Button`'s own defaults, so it has to be asserted explicitly now that the
 * control is hand-rolled.
 */
val PANEL_BUTTON_MIN_HEIGHT = 48.dp

@Composable
fun AvionicsPanelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inProgress: Boolean = false,
    pulsing: Boolean = false,
    caption: String? = null,
    ledColor: Color = FlightOrange,
    contentDescription: String? = null,
) {
    // `interactable`, not `enabled`, drives every visual below: an in-progress control is not
    // `enabled = false` (the caller still wants the click wired so a second tap is absorbed rather
    // than dead), but it must still *look* disabled -- a lit LED/full-brightness label mid-export
    // reads as "you can tap this again" (PR #339 review, `@rev`).
    val interactable = enabled && !inProgress
    val displayLedColor = if (interactable) ledColor else TextDim
    val labelColor = if (interactable) TextStencil else TextDim
    val captionColor = if (interactable) TextMuted else TextDim
    val borderColor = if (interactable) ledColor.copy(alpha = 0.45f) else CockpitBorder

    Surface(
        onClick = onClick,
        enabled = interactable,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PANEL_BUTTON_MIN_HEIGHT)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        shape = RoundedCornerShape(RADIUS_SM),
        color = if (interactable) CockpitPanelRaised else CockpitPanel,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = ledColor,
                    strokeWidth = 2.dp,
                )
            } else if (pulsing && interactable) {
                // The owner's addition to #335: the LED pulses while the action it represents is
                // live (forward recording in progress), the same way the annunciator's own LED
                // pulses while the buffer is capturing -- see [PulsingDot], shared by both rather
                // than reimplemented here.
                PulsingDot(color = displayLedColor, size = 9.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(displayLedColor, CircleShape)
                        .then(
                            if (interactable) {
                                Modifier.background(
                                    Brush.radialGradient(
                                        colors = listOf(displayLedColor.copy(alpha = 0.55f), Color.Transparent),
                                    ),
                                    CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 0.06.sp,
                    color = labelColor,
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = captionColor,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * A softly breathing status LED: matches the annunciator's own recording indicator
 * (`DashboardScreen`'s green pulse, issue #6) exactly -- same 900ms linear tween, same
 * 0.35..1f alpha swing, reversed rather than restarted -- so every "this is live right now" light
 * in the app shares one timing and one feel, whatever colour it happens to be lit. Introduced for
 * issue #335's [AvionicsPanelButton] `pulsing` state (the orange LIVE button LED, per the owner's
 * call) and reused by the annunciator, which used to carry its own private copy of this animation.
 *
 * `clearAndSetSemantics {}` mirrors the annunciator's own dot: a purely decorative, infinitely
 * animating node has nothing a screen reader should announce, and TalkBack polling an
 * `infiniteRepeatable` for a description would be actively unhelpful.
 */
@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val transition = rememberInfiniteTransition(label = "avionics-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avionics-pulse-alpha",
    )
    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape)
            .clearAndSetSemantics {},
    )
}

/**
 * Lays out the two `AvionicsPanelButton`s ([save] and [live]) as a "control pair": side by side
 * whenever there is width to spare, stacked full-width below [PANEL_ROW_STACK_BELOW] -- the app's
 * smallest supported width (360dp, `CompactHarnessApp`) leaves only ~280dp inside the engine card
 * after its own padding, which is not enough for two labelled buttons with captions side by side
 * without truncating the caption issue #335 needs to keep visible (buffer duration / disable
 * reason). Stacking there costs vertical space, never legibility.
 */
val PANEL_ROW_STACK_BELOW = 400.dp

@Composable
fun AvionicsPanelButtonRow(
    modifier: Modifier = Modifier,
    save: @Composable () -> Unit,
    live: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth < PANEL_ROW_STACK_BELOW) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                save()
                live()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) { save() }
                Box(modifier = Modifier.weight(1f)) { live() }
            }
        }
    }
}

/** How far the tag must be pulled before releasing it counts as removing it (issue #284). */
val RBF_PULL_THRESHOLD = 72.dp

/** How far the tag slides in from as it is put back on the panel. */
private val RBF_ENTRY_SLIDE = 24.dp

private const val RBF_ENTRY_DURATION_MS = 320

/** How much further the tag carries on in the pull direction as it comes off the panel. */
private val RBF_EXIT_TRAVEL = 40.dp

private const val RBF_EXIT_DURATION_MS = 180

/** Degrees the tag swings about its grommet at a full-threshold pull. */
private const val RBF_MAX_SWING_DEGREES = 7f

/**
 * "REMOVE BEFORE FLIGHT" ribbon / tag banner matching `docs/design/model.html`'s `.rbf-tag`.
 * Rendered when capture is idle / on standby.
 *
 * Pass [onRemove] to make it behave like the real thing: the tag can be dragged off the panel in
 * any direction, and letting go past [RBF_PULL_THRESHOLD] removes it and runs [onRemove] once
 * (issue #284). Left null -- the default -- it is the passive banner it has always been.
 *
 * This is deliberately an *extra* path to the same action, never the only one. The dashboard's
 * engine `Switch` stays exactly where it was and does exactly the same thing; a drag is a nicer
 * way to reach it, not a toll gate in front of it. Accordingly the gesture is never the only
 * affordance: when it is live the tag also carries a named accessibility action
 * ([removeActionLabel]) so TalkBack, Switch Access and keyboard users get the same one-step route
 * without having to produce a drag, and the tag's own text stays readable as plain status.
 *
 * A dragging tag needs a host that neither clips it nor lets it take part in the layout, and this
 * composable does not provide one -- it is an ordinary `fillMaxWidth()` Surface. On the dashboard
 * that host is `RbfTagOverlay` in `DashboardScreen`, which lays it over the mic-input-level rack
 * from a zero-size layout outside the card's clipping `Surface`. Anywhere else, put it somewhere
 * with the same two properties or the tag will be cut off at the first ancestor that clips.
 *
 * @param onRemove invoked at most once, when the tag is pulled clear. Null makes the tag inert.
 * @param removeEnabled mirrors the engine switch's own enabled gate; false freezes the gesture in
 *   place (the tag does not even move) so the two controls can never disagree about whether the
 *   recorder may be armed right now.
 * @param removeActionLabel the accessibility-action label. Required for the action to be offered.
 * @param playEntryAnimation true when the tag is being put *back* on the panel, so it slides and
 *   fades in instead of appearing between two frames. False on first render, so the screen does
 *   not animate on arrival.
 */
@Composable
fun RemoveBeforeFlightTag(
    text: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    removeActionLabel: String? = null,
    playEntryAnimation: Boolean = false,
) {
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val density = LocalDensity.current
    val thresholdPx = with(density) { RBF_PULL_THRESHOLD.toPx() }
    val entrySlidePx = with(density) { RBF_ENTRY_SLIDE.toPx() }
    val exitTravelPx = with(density) { RBF_EXIT_TRAVEL.toPx() }

    val currentOnRemove by rememberUpdatedState(onRemove)
    val gate = remember(thresholdPx) {
        RemoveBeforeFlightDragGate(thresholdPx) { currentOnRemove?.invoke() }
    }
    val gestureLive = onRemove != null && removeEnabled
    gate.enabled = gestureLive

    val scope = rememberCoroutineScope()
    val offset = remember {
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    }

    // 0 = just being put back on the panel, 1 = settled. Starts settled unless this is a return
    // and the user still wants animations, so neither a cold start nor an animations-off device
    // ever sees a transition here.
    val entry = remember {
        Animatable(if (playEntryAnimation && animationsEnabled) 0f else 1f)
    }

    // 1 = on the panel, 0 = off it. Only the successful pull drives this, so it is separate from
    // [entry]: a tag being put back on the panel and a tag coming off it are different motions in
    // opposite directions, and multiplying the two alphas keeps either one from having to know
    // about the other.
    val departure = remember { Animatable(1f) }

    LaunchedEffect(entry) {
        if (entry.value != 1f) {
            entry.animateTo(1f, tween(RBF_ENTRY_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }

    suspend fun settleHome() {
        if (animationsEnabled) {
            offset.animateTo(
                targetValue = Offset.Zero,
                animationSpec = spring(
                    dampingRatio = 0.42f,
                    stiffness = Spring.StiffnessLow,
                    visibilityThreshold = Offset.VisibilityThreshold,
                ),
            )
        } else {
            offset.snapTo(Offset.Zero)
        }
    }

    fun settleBack() {
        scope.launch { settleHome() }
    }

    /**
     * The exit motion for a successful pull: the tag keeps going the way it was pulled and fades
     * out, instead of hanging at the release position for the whole pending window (PR #320 review,
     * `@rev` finding 5). With animations off it goes straight to that animation's end state -- off
     * the panel -- which is a loss of motion, never of function.
     */
    fun peelOff() {
        scope.launch {
            if (!animationsEnabled) {
                departure.snapTo(0f)
                return@launch
            }
            val released = offset.value
            val travelled = released.getDistance()
            val direction = if (travelled > 0f) released / travelled else Offset(1f, 0f)
            launch {
                offset.animateTo(
                    targetValue = released + direction * exitTravelPx,
                    animationSpec = tween(RBF_EXIT_DURATION_MS, easing = FastOutSlowInEasing),
                )
            }
            departure.animateTo(0f, tween(RBF_EXIT_DURATION_MS, easing = LinearEasing))
        }
    }

    // What happens when the gesture stops or starts being live while the tag is still composed.
    // Both directions matter, and neither used to be handled (PR #320 review, `@rev` findings 1 and
    // 2): the tag stays on the panel through the whole pending window, and comes back to it
    // whenever a start fails into `Error` or `toggleEngine`'s timeout backstop clears `pending`
    // with the recorder still idle.
    LaunchedEffect(gate, gestureLive) {
        if (gestureLive) {
            // The pull did not take. Give the drag route back and refit the tag, rather than
            // leaving a dead affordance lying where the finger dropped it.
            if (gate.rearm()) {
                offset.snapTo(Offset.Zero)
                departure.snapTo(1f)
                if (animationsEnabled) {
                    entry.snapTo(0f)
                    entry.animateTo(1f, tween(RBF_ENTRY_DURATION_MS, easing = FastOutSlowInEasing))
                } else {
                    entry.snapTo(1f)
                }
            }
        } else if (gate.standDown()) {
            // The detector has just been uninstalled mid-drag, so no onDragEnd/onDragCancel is
            // coming for the gesture in progress. Put the tag back by hand.
            settleHome()
        }
    }

    // Only claim touches while the gesture can actually do something. The tag sits inside the
    // dashboard's vertical scroll and detectDragGestures consumes the slop crossing as soon as it
    // wins the pointer, so an always-installed detector would swallow scrolls that start on the
    // tag even in the states where the tag refuses to move.
    val pullGesture = if (!gestureLive) {
        Modifier
    } else {
        Modifier.pointerInput(gate) {
            detectDragGestures(
                onDragStart = {
                    scope.launch {
                        offset.stop()
                        offset.snapTo(Offset(gate.offsetX, gate.offsetY))
                    }
                },
                onDrag = { change, delta ->
                    if (gate.drag(delta.x, delta.y)) {
                        change.consume()
                        scope.launch { offset.snapTo(Offset(gate.offsetX, gate.offsetY)) }
                    }
                },
                onDragEnd = {
                    // The gate runs onRemove itself on a PULL; all that is left here is the
                    // motion -- home if it was not pulled far enough, off the panel if it was.
                    when (gate.release()) {
                        RemoveBeforeFlightDragGate.Release.SPRING_BACK -> settleBack()
                        RemoveBeforeFlightDragGate.Release.PULL -> peelOff()
                        RemoveBeforeFlightDragGate.Release.INERT -> Unit
                    }
                },
                onDragCancel = {
                    if (gate.cancel() == RemoveBeforeFlightDragGate.Release.SPRING_BACK) {
                        settleBack()
                    }
                },
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offset.value.x - (1f - entry.value) * entrySlidePx
                translationY = offset.value.y
                alpha = entry.value * departure.value
                // A real tag hangs off its grommet, so it swings rather than sliding flat.
                rotationZ = (offset.value.x / thresholdPx).coerceIn(-1f, 1f) * RBF_MAX_SWING_DEGREES
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(pullGesture)
            .semantics(mergeDescendants = true) {
                val action = currentOnRemove
                if (action != null && removeEnabled && removeActionLabel != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(removeActionLabel) {
                            action()
                            true
                        },
                    )
                }
            },
        shape = RoundedCornerShape(RADIUS_RIVET),
        color = SafetyRedTag,
        border = BorderStroke(1.dp, WarningRed),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grommet circle
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape)
                    .border(1.5.dp, Color(0xFF7F1D1D), CircleShape),
            )
            Text(
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.08.sp,
                color = Color.White,
            )
        }
    }
}

/**
 * Flight Data Recorder (FDR) Flight Tape / Buffer RAM progress ruler track
 * matching `docs/design/model.html`'s `.tape-ruler-track` and `.tape-fill`.
 */
@Composable
fun FlightTapeRulerTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E293B)),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val tickSpacing = 8.dp.toPx()
            val tickCount = (width / tickSpacing).toInt()

            // Draw ruler tick marks
            for (i in 0..tickCount) {
                val x = i * tickSpacing
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Draw orange tape fill
            if (clampedFraction > 0f) {
                val fillWidth = width * clampedFraction
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFEA580C),
                            FlightOrange,
                            FlightOrangeLight,
                        ),
                        startX = 0f,
                        endX = fillWidth,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(fillWidth, height),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )

                // White leading needle mark
                val needleWidth = 3.dp.toPx()
                val needleX = (fillWidth - needleWidth).coerceAtLeast(0f)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(needleX, 0f),
                    size = Size(needleWidth, height),
                )
            }
        }
    }
}

/**
 * Dashed divider matching `docs/design/model.html`'s `1px dashed rgba(255, 255, 255, 0.1)`.
 */
@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.12f),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
        )
    }
}
