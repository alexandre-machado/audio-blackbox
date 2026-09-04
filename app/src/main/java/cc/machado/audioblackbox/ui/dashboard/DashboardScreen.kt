package cc.machado.audioblackbox.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.ui.ScreenHeader
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.AvionicsCardHeaderBar
import cc.machado.audioblackbox.ui.theme.AvionicsGreen
import cc.machado.audioblackbox.ui.theme.AvionicsGreenGlow
import cc.machado.audioblackbox.ui.theme.AvionicsTag
import cc.machado.audioblackbox.ui.theme.CARD_INNER_PADDING
import cc.machado.audioblackbox.ui.theme.CARD_SHAPE
import cc.machado.audioblackbox.ui.theme.CautionAmber
import cc.machado.audioblackbox.ui.theme.CautionAmberGlow
import cc.machado.audioblackbox.ui.theme.CockpitBorder
import cc.machado.audioblackbox.ui.theme.CockpitBorderStrong
import cc.machado.audioblackbox.ui.theme.CockpitPanel
import cc.machado.audioblackbox.ui.theme.CockpitSlate
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightTapeRulerTrack
import cc.machado.audioblackbox.ui.theme.RADIUS_SM
import cc.machado.audioblackbox.ui.theme.RemoveBeforeFlightTag
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import cc.machado.audioblackbox.ui.theme.SECTION_SPACING
import cc.machado.audioblackbox.ui.theme.TextDim
import cc.machado.audioblackbox.ui.theme.TextMuted
import cc.machado.audioblackbox.ui.theme.WarningRed
import cc.machado.audioblackbox.ui.theme.WarningRedGlow
import cc.machado.audioblackbox.ui.theme.primaryCtaButtonColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hosts [DashboardViewModel] and renders [DashboardScreen] against its live state. The seam
 * between this and [DashboardScreen] exists so every visual state is a plain, previewable
 * function of a [DashboardUiState] value -- see the `@Preview`s below -- without needing a real
 * [cc.machado.audioblackbox.service.RecorderService] running.
 */
@Composable
fun DashboardRoute(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        uiState = uiState,
        onToggleEngine = viewModel::toggleEngine,
        onSaveRecent = viewModel::requestSave,
        onDismissSaveNotice = viewModel::dismissSaveNotice,
        onStartForwardRecording = viewModel::startForwardRecording,
        onStopForwardRecording = viewModel::stopForwardRecording,
        onDismissForwardNotice = viewModel::dismissForwardRecordingNotice,
        modifier = modifier,
    )
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleEngine: () -> Unit,
    onSaveRecent: () -> Unit,
    onDismissSaveNotice: () -> Unit,
    onStartForwardRecording: () -> Unit,
    onStopForwardRecording: () -> Unit,
    onDismissForwardNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        AppHeader()
        EngineCard(
            uiState = uiState,
            onToggleEngine = onToggleEngine,
            // Draw order, not layout. The REMOVE BEFORE FLIGHT tag is an overlay hosted inside this
            // card's slot (see [EngineCard]), and while it is dragged it has to be able to travel
            // over the cards below it, which are later siblings in this Column and would otherwise
            // paint on top of it. Nothing moves: zIndex only reorders drawing and hit-testing.
            modifier = Modifier.zIndex(1f),
        )
        SaveSection(uiState, onSaveRecent)
        ForwardRecordingSection(
            forwardState = uiState.forwardRecordingState,
            onStart = onStartForwardRecording,
            onStop = onStopForwardRecording,
        )
        if (uiState.saveState is SaveUiState.Success || uiState.saveState is SaveUiState.Error) {
            SaveOutcomeNotice(
                saveState = uiState.saveState,
                onDismiss = onDismissSaveNotice,
                onRetry = onSaveRecent,
            )
        }
        if (uiState.forwardRecordingState is ForwardRecordingUiState.Success ||
            uiState.forwardRecordingState is ForwardRecordingUiState.Error
        ) {
            ForwardOutcomeNotice(
                forwardState = uiState.forwardRecordingState,
                onDismiss = onDismissForwardNotice,
                onRetry = onStartForwardRecording,
            )
        }
    }
}

@Composable
private fun AppHeader() {
    ScreenHeader(
        icon = painterResource(R.drawable.ic_waveform_mic),
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.dashboard_app_subtitle),
    )
}

/**
 * Remembers that the REMOVE BEFORE FLIGHT tag has been off the panel at least once during this
 * composition of [EngineCard], which is what tells the tag apart from "the screen just opened"
 * (issue #284): a returning tag animates back on, a freshly-opened screen does not animate at all.
 *
 * Deliberately a plain holder rather than a `MutableState`: the flag is written in the frame where
 * the tag is *not* composed and read in a later frame where it is, so it never needs to invalidate
 * anything, and making it observable would only add a needless recomposition (and a write-during-
 * composition to a value read in the same pass).
 */
private class RbfTagReturn {
    var hasBeenAway: Boolean = false
}

/**
 * Where the mic-input-level (VU) rack sits inside [EngineCard]'s slot, in that slot's own pixels,
 * so [RbfTagOverlay] can lay the REMOVE BEFORE FLIGHT tag over it.
 *
 * Measured rather than hardcoded because the rack's position moves with font scale, locale and
 * width, and an overlay pinned to a guessed offset would drift off it. Both edges of the
 * subtraction move together when the dashboard scrolls, so [bounds] does not change during a
 * scroll and the overlay is not re-laid-out for one: the tag scrolls with the card because it is
 * inside the same scrolling Column, not because anything recomputes per frame.
 */
private class RbfOverlaySlot {
    private var host: LayoutCoordinates? = null
    private var anchor: LayoutCoordinates? = null

    /** Null until both the slot and the rack have been positioned once. */
    var bounds: IntRect? by mutableStateOf(null)
        private set

    fun onHost(coordinates: LayoutCoordinates) {
        host = coordinates
        recompute()
    }

    fun onAnchor(coordinates: LayoutCoordinates) {
        anchor = coordinates
        recompute()
    }

    private fun recompute() {
        val host = host ?: return
        val anchor = anchor ?: return
        if (!host.isAttached || !anchor.isAttached) return
        val topLeft = host.localPositionOf(anchor, Offset.Zero).round()
        val next = IntRect(topLeft, anchor.size)
        if (next != bounds) bounds = next
    }
}

/**
 * Hosts the REMOVE BEFORE FLIGHT tag over the mic-input-level rack (issue #284, the owner's verdict
 * after testing the gesture on the device: *"poderia ficar por cima do 'mic input level' de modo
 * que o card abaixo não sofra resize, e a etiqueta sobreponha qualquer elemento da tela"*).
 *
 * Two invariants, and how each is met:
 *
 * **Showing or hiding the tag moves and resizes nothing.** `Modifier.matchParentSize()` is
 * precisely the "do not take part in sizing this Box" contract: the surrounding Box is measured
 * from [EngineChassisCard] alone, and this overlay is then measured a second time against whatever
 * size that produced. So the card below cannot reflow when the tag appears or disappears -- not
 * because the tag happens to be shorter than the card, but because the tag's measurement is never
 * an input to anyone else's.
 *
 * **The tag draws above everything and is not clipped while dragged.** Nothing in the chain from
 * here up to the dashboard's scroll viewport clips: it is Boxes all the way, whereas the card is a
 * [androidx.compose.material3.Surface] and *does* clip, which is why the tag had to leave it. The
 * `zIndex` at the [EngineCard] call site then carries a dragged tag over the sibling cards below,
 * which would otherwise paint on top of it. The only boundary left is the `verticalScroll`
 * viewport -- the visible screen -- and the floating bottom bar, which lives in `AppScaffold`
 * outside this composition and still draws over the tag. That last one is deliberate: it is what
 * stops a draggable element from covering the app's own navigation, and it is what keeps
 * `ScreenLayoutTest`'s "content stays clear of the bar" assertion meaningful.
 *
 * The overlay is given the whole slot's size rather than a zero one so that the tag at rest is
 * inside its parent's bounds and is unambiguously hit-testable. Once a drag is under way the
 * pointer is held by `detectDragGestures` for the rest of the gesture, so the tag keeps following
 * the finger even where it has travelled outside every bound in sight.
 */
@Composable
private fun BoxScope.RbfTagOverlay(
    slot: RbfOverlaySlot,
    content: @Composable () -> Unit,
) {
    val bounds = slot.bounds
    Layout(content = content, modifier = Modifier.matchParentSize()) { measurables, constraints ->
        val width = bounds?.width ?: constraints.maxWidth
        val placeable = measurables.first().measure(Constraints.fixedWidth(width))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Before the first layout pass has positioned the rack there is nowhere honest to put
            // the tag, so it is measured but not placed for that one frame rather than flashing at
            // the slot's origin and jumping.
            if (bounds != null) {
                placeable.place(
                    x = bounds.left,
                    y = bounds.top + (bounds.height - placeable.height) / 2,
                )
            }
        }
    }
}

/**
 * The engine card's slot: the chassis card itself, plus the REMOVE BEFORE FLIGHT tag laid over its
 * mic-input-level rack.
 *
 * The tag used to be an item in the card's own Column, between the annunciator and the VU rack,
 * which meant showing or hiding it reflowed everything below it -- and, once it became draggable
 * (issue #284), that the card's `Surface` clipped it the moment it left the card. It is now an
 * overlay instead, on the owner's call after testing the gesture on the device. [RbfTagOverlay]
 * carries the whole argument for how that stays true.
 */
@Composable
private fun EngineCard(
    uiState: DashboardUiState,
    onToggleEngine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tagReturn = remember { RbfTagReturn() }
    val rbfSlot = remember { RbfOverlaySlot() }

    Box(modifier = modifier.fillMaxWidth().onGloballyPositioned(rbfSlot::onHost)) {
        EngineChassisCard(
            uiState = uiState,
            onToggleEngine = onToggleEngine,
            rbfSlot = rbfSlot,
        )

        // Standby / "REMOVE BEFORE FLIGHT" ribbon tag.
        //
        // Pulling the tag off arms the recorder (issue #284) by calling the very same
        // onToggleEngine() the Switch calls -- two ways in, one destination, so the gesture can be
        // dropped at any time without leaving the screen without a way to start recording.
        //
        // Whether the tag is here at all, and whether a pull may arm, are both
        // RemoveBeforeFlightTagPolicy's to answer: they are the rule that keeps the gesture
        // one-way, and they are exhaustively tested over every CaptureStatus rather than defended
        // by reading (PR #320 review, `@rev` finding 4).
        if (RemoveBeforeFlightTagPolicy.isOnPanel(uiState.captureStatus, uiState.engineSwitch)) {
            RbfTagOverlay(rbfSlot) {
                RemoveBeforeFlightTag(
                    text = stringResource(R.string.dashboard_rbf_tag),
                    onRemove = onToggleEngine.takeIf {
                        RemoveBeforeFlightTagPolicy.mayArm(uiState.engineSwitch)
                    },
                    removeEnabled = uiState.engineSwitch.enabled,
                    removeActionLabel = stringResource(R.string.dashboard_rbf_remove_action),
                    playEntryAnimation = tagReturn.hasBeenAway,
                )
            }
        } else {
            // Recording: the tag is off the panel. Remember that, so that when the recorder is
            // switched off the tag is animated back on instead of blinking into existence.
            tagReturn.hasBeenAway = true
        }
    }
}

@Composable
private fun EngineChassisCard(
    uiState: DashboardUiState,
    onToggleEngine: () -> Unit,
    rbfSlot: RbfOverlaySlot,
) {
    val status = uiState.captureStatus
    val (annunciatorTitleRes, annunciatorSubRes) = when (status) {
        is CaptureStatus.Idle -> R.string.dashboard_annunciator_standby to R.string.dashboard_annunciator_standby_sub
        is CaptureStatus.Recording -> R.string.dashboard_annunciator_armed to R.string.dashboard_annunciator_armed_sub
        is CaptureStatus.Paused -> R.string.dashboard_annunciator_paused to R.string.dashboard_annunciator_paused_sub
        is CaptureStatus.Error -> R.string.dashboard_annunciator_error to R.string.dashboard_annunciator_error_sub
    }
    val (labelRes, explanationRes) = when (status) {
        is CaptureStatus.Idle -> R.string.dashboard_status_idle to R.string.dashboard_idle_explanation
        is CaptureStatus.Recording -> R.string.dashboard_status_recording to R.string.dashboard_recording_explanation
        is CaptureStatus.Paused -> R.string.dashboard_status_paused to R.string.dashboard_paused_explanation
        is CaptureStatus.Error -> R.string.dashboard_status_error to status.reason.toUserMessageRes()
    }
    val label = stringResource(labelRes)
    val explanation = stringResource(explanationRes)
    val announcement = stringResource(R.string.dashboard_status_announcement, label)

    AvionicsCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Chassis Header / Data Plate Bar
            AvionicsCardHeaderBar(
                label = stringResource(R.string.dashboard_card_annunciator_label),
                tag = {
                    AvionicsTag(text = stringResource(R.string.dashboard_engine_sample_rate))
                },
            )

            // Korry-Style Annunciator Switch Status
            Surface(
                shape = RoundedCornerShape(RADIUS_SM),
                color = CockpitSlate,
                border = BorderStroke(1.5.dp, CockpitBorderStrong),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (status is CaptureStatus.Recording) {
                            RecordingPulse()
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor(status), CircleShape),
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(annunciatorTitleRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.05.sp,
                                color = statusColor(status),
                            )
                            Text(
                                text = stringResource(annunciatorSubRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted,
                            )
                        }
                    }
                    Switch(
                        checked = uiState.engineSwitch.checked,
                        onCheckedChange = { onToggleEngine() },
                        enabled = uiState.engineSwitch.enabled,
                        modifier = Modifier.testTag(ENGINE_SWITCH_TEST_TAG),
                    )
                }
            }

            // VU Meter Recessed Rack -- also the REMOVE BEFORE FLIGHT tag's anchor: the tag is laid
            // over this rack rather than sitting above it in the flow, so it costs the card no
            // height and the rack cannot be reflowed by it appearing or disappearing.
            Surface(
                shape = RoundedCornerShape(RADIUS_SM),
                color = CockpitSlate,
                border = BorderStroke(1.dp, CockpitBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned(rbfSlot::onAnchor),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_card_vu_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                        )
                        Text(
                            text = uiState.inputLevel.let { level ->
                                if (level <= 0f) {
                                    stringResource(R.string.dashboard_mic_level_no_signal)
                                } else {
                                    stringResource(R.string.dashboard_mic_level_dbfs, dbfsFor(level))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MicLevelMeter(level = uiState.inputLevel)
                }
            }

            // FDR Flight Tape / Circular Buffer Visualizer
            BufferRamVisualizer(uiState)

            if (explanation.isNotEmpty()) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status is CaptureStatus.Error && status.message.isNotBlank()) {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Segmented meter driven by the microphone's real peak level (issue #175).
 *
 * [level] comes from [cc.machado.audioblackbox.audio.AudioLevel.peakLevel] over the PCM actually
 * captured. It previously came from an `infiniteRepeatable` animation swinging 0.25..0.75 next to
 * a hardcoded "45%", which meant the meter reported a healthy signal whether or not the microphone
 * was hearing anything -- including while capture was silenced by another app.
 *
 * A short `animateFloatAsState` smooths the bar between measurements. That is presentation only:
 * it interpolates towards a value the microphone really produced and settles on it, unlike the
 * previous animation, which *was* the value. Zero must therefore be reachable and visibly empty --
 * `activeSegments` floors rather than coercing to a minimum of 1, so silence shows no lit segment
 * at all.
 */
@Composable
private fun MicLevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    segmentCount: Int = 20,
) {
    val smoothedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "mic-level",
    )

    val activeSegments = (smoothedLevel * segmentCount).toInt().coerceIn(0, segmentCount)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until segmentCount) {
            val isActive = i < activeSegments
            val color = if (isActive) {
                if (i >= (segmentCount * 0.85f).toInt()) {
                    WarningRed
                } else if (i >= (segmentCount * 0.65f).toInt()) {
                    CautionAmber
                } else {
                    AvionicsGreen
                }
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun BufferRamVisualizer(uiState: DashboardUiState) {
    val bufferedMin = uiState.bufferedMillis.toFloat() / 60_000f
    val capacityMin = uiState.capacityMillis.toFloat() / 60_000f
    val percentage = if (capacityMin > 0f) ((bufferedMin / capacityMin) * 100f).toInt().coerceIn(0, 100) else 0
    val fraction = if (uiState.capacityMillis > 0) {
        (uiState.bufferedMillis.toFloat() / uiState.capacityMillis.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val bufferedLabel = formatMillisAsClock(uiState.bufferedMillis)
    val capacityLabel = formatMillisAsClock(uiState.capacityMillis)
    val progressCd = stringResource(R.string.dashboard_buffer_status_cd, bufferedLabel, capacityLabel)

    Surface(
        shape = RoundedCornerShape(RADIUS_SM),
        color = CockpitSlate,
        border = BorderStroke(1.dp, CockpitBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_card_tape_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                )
                Text(
                    text = String.format(Locale.US, "%.1f / %.0f min (%d%%)", bufferedMin, capacityMin, percentage),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = FlightOrange,
                )
            }

            Box(modifier = Modifier.semantics { contentDescription = progressCd }) {
                FlightTapeRulerTrack(fraction = fraction)
            }

            Text(
                text = stringResource(R.string.dashboard_buffer_ram_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingPulse() {
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(AvionicsGreen, CircleShape)
            .clearAndSetSemantics {},
    )
}

private fun statusColor(status: CaptureStatus): Color = when (status) {
    is CaptureStatus.Idle -> Color(0xFF64748B) // TextDim
    is CaptureStatus.Paused -> CautionAmber
    is CaptureStatus.Error -> WarningRed
    is CaptureStatus.Recording -> AvionicsGreen
}

@Composable
private fun SaveSection(uiState: DashboardUiState, onSaveRecent: () -> Unit) {
    var lastSaveClickTime by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val isExporting = uiState.saveState is SaveUiState.Exporting
    val canSave = uiState.bufferedMillis > 0 && !isExporting
    val bufferedClock = formatMillisAsClock(uiState.bufferedMillis)
    val capacityMinutes = (uiState.capacityMillis / 60_000L).toInt()

    val explanation = when {
        isExporting -> stringResource(R.string.dashboard_save_exporting_body)
        uiState.bufferedMillis == 0L -> stringResource(R.string.dashboard_save_disabled_no_audio)
        uiState.isBufferFull -> stringResource(R.string.dashboard_save_explanation_full, capacityMinutes)
        else -> stringResource(R.string.dashboard_save_explanation_partial, bufferedClock)
    }

    val buttonCd = when {
        isExporting -> stringResource(R.string.dashboard_save_exporting_title)
        uiState.bufferedMillis == 0L -> stringResource(R.string.dashboard_save_disabled_no_audio)
        uiState.isBufferFull -> stringResource(R.string.dashboard_save_button_cd_full, capacityMinutes)
        else -> stringResource(R.string.dashboard_save_button_cd_partial, bufferedClock)
    }

    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.dashboard_card_lookback_label),
            )
            Text(text = explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastSaveClickTime >= 500L) {
                        lastSaveClickTime = now
                        onSaveRecent()
                    }
                },
                enabled = canSave,
                colors = primaryCtaButtonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SAVE_BUTTON_TEST_TAG)
                    .semantics { contentDescription = buttonCd },
            ) {
                if (isExporting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_save_exporting_title),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.dashboard_save_button),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForwardRecordingSection(
    forwardState: ForwardRecordingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.dashboard_card_forward_label),
            )
            when (forwardState) {
                is ForwardRecordingUiState.Recording -> {
                    val elapsedFormatted = formatMillisAsClock(forwardState.elapsedMillis)
                    val elapsedText = stringResource(R.string.dashboard_forward_elapsed_label, elapsedFormatted)
                    val elapsedCd = stringResource(R.string.dashboard_forward_elapsed_cd, elapsedFormatted)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = elapsedCd
                            },
                    ) {
                        Text(
                            text = elapsedText,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag(FORWARD_ELAPSED_TEST_TAG),
                        )
                        Text(
                            text = forwardState.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Button(
                        onClick = onStop,
                        colors = primaryCtaButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FORWARD_STOP_BUTTON_TEST_TAG),
                    ) {
                        Text(text = stringResource(R.string.dashboard_forward_stop_button))
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.dashboard_forward_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onStart,
                        colors = primaryCtaButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FORWARD_START_BUTTON_TEST_TAG),
                    ) {
                        Text(text = stringResource(R.string.dashboard_forward_start_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveOutcomeNotice(
    saveState: SaveUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val titleRes: Int
    val body: String
    val dismissible: Boolean
    when (saveState) {
        SaveUiState.Idle -> return
        SaveUiState.Exporting -> {
            titleRes = R.string.dashboard_save_exporting_title
            body = stringResource(R.string.dashboard_save_exporting_body)
            dismissible = false
        }
        is SaveUiState.Success -> {
            titleRes = R.string.dashboard_save_success_title
            body = stringResource(R.string.dashboard_save_success_body, saveState.displayName)
            dismissible = true
        }
        is SaveUiState.Error -> {
            titleRes = R.string.dashboard_save_error_title
            body = saveState.message
            dismissible = true
        }
    }
    val title = stringResource(titleRes)
    val announcement = stringResource(R.string.dashboard_save_outcome_announcement, title, body)
    val context = LocalContext.current

    AvionicsCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.dashboard_card_save_outcome_label),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when (saveState) {
                    is SaveUiState.Success -> AvionicsGreen
                    is SaveUiState.Error -> WarningRed
                    else -> FlightOrange
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (saveState is SaveUiState.Success) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                }
            } else if (saveState is SaveUiState.Error) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RADIUS_SM),
                    color = CockpitSlate,
                    border = BorderStroke(1.dp, CockpitBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_error_telemetry_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = FlightOrange,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_error_telemetry_code, saveState.reason.name),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = stringResource(
                                R.string.dashboard_error_telemetry_preset,
                                "${saveState.qualityPreset.name} (${saveState.qualityPreset.sampleRateHz} Hz)",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        // Frozen at the moment of failure (saveState.*), not read live off uiState --
                        // see SaveUiState.Error's doc (issue #206, `@rev` finding on PR #207): capture
                        // keeps running after a save failure, so uiState.bufferedMillis/capacityMillis
                        // keep ticking for as long as this notice stays on screen.
                        val capacityMin = (saveState.capacityMillis / 60_000L).toInt()
                        val bufferedSec = "${saveState.bufferedMillis / 1000L}s"
                        Text(
                            text = stringResource(R.string.dashboard_error_telemetry_buffer, capacityMin, bufferedSec),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                val report = remember(saveState) {
                    DiagnosticsReportHelper.buildSaveErrorReport(
                        reason = saveState.reason.name,
                        message = saveState.message,
                        preset = saveState.qualityPreset,
                        capacityMinutes = (saveState.capacityMillis / 60_000L).toInt(),
                        bufferedMillis = saveState.bufferedMillis,
                        timestampMillis = saveState.timestampMillis,
                    )
                }
                val chooserTitle = stringResource(R.string.dashboard_error_share_chooser_title)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { DiagnosticsReportHelper.copyToClipboard(context, report) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.dashboard_error_action_copy))
                    }
                    Button(
                        onClick = { DiagnosticsReportHelper.shareReport(context, report, chooserTitle) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.dashboard_error_action_share))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                    }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.dashboard_error_action_retry))
                    }
                }
            } else if (dismissible) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ForwardOutcomeNotice(
    forwardState: ForwardRecordingUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val titleRes: Int
    val body: String
    when (forwardState) {
        is ForwardRecordingUiState.Success -> {
            titleRes = R.string.dashboard_forward_success_title
            body = stringResource(R.string.dashboard_forward_success_body, forwardState.displayName)
        }
        is ForwardRecordingUiState.Error -> {
            titleRes = R.string.dashboard_forward_error_title
            body = forwardState.message
        }
        else -> return
    }
    val title = stringResource(titleRes)
    val announcement = stringResource(R.string.dashboard_forward_outcome_announcement, title, body)
    val context = LocalContext.current

    AvionicsCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.dashboard_card_forward_outcome_label),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when (forwardState) {
                    is ForwardRecordingUiState.Success -> AvionicsGreen
                    is ForwardRecordingUiState.Error -> WarningRed
                    else -> FlightOrange
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (forwardState is ForwardRecordingUiState.Error) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RADIUS_SM),
                    color = CockpitSlate,
                    border = BorderStroke(1.dp, CockpitBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_error_telemetry_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = FlightOrange,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_error_telemetry_code, forwardState.reason.name),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = stringResource(
                                R.string.dashboard_error_telemetry_preset,
                                "${forwardState.qualityPreset.name} (${forwardState.qualityPreset.sampleRateHz} Hz)",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                // Frozen at the moment of failure (forwardState.*), not read live off uiState --
                // see ForwardRecordingUiState.Error's doc (issue #206, `@rev` finding on PR #207).
                val report = remember(forwardState) {
                    DiagnosticsReportHelper.buildForwardErrorReport(
                        reason = forwardState.reason.name,
                        message = forwardState.message,
                        preset = forwardState.qualityPreset,
                        capacityMinutes = (forwardState.capacityMillis / 60_000L).toInt(),
                        timestampMillis = forwardState.timestampMillis,
                    )
                }
                val chooserTitle = stringResource(R.string.dashboard_error_share_chooser_title)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { DiagnosticsReportHelper.copyToClipboard(context, report) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.dashboard_error_action_copy))
                    }
                    Button(
                        onClick = { DiagnosticsReportHelper.shareReport(context, report, chooserTitle) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.dashboard_error_action_share))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                    }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.dashboard_error_action_retry))
                    }
                }
            } else {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                }
            }
        }
    }
}

// ---- Previews ----

private fun previewState(
    status: CaptureStatus,
    bufferedMillis: Long = 0L,
    capacityMinutes: Int = 30,
    saveState: SaveUiState = SaveUiState.Idle,
    forwardRecordingState: ForwardRecordingUiState = ForwardRecordingUiState.Idle,
    enginePending: Boolean = false,
): DashboardUiState = DashboardViewModel.mapUiState(
    captureState = when (status) {
        is CaptureStatus.Idle -> CaptureState.Idle
        is CaptureStatus.Recording -> CaptureState.Recording
        is CaptureStatus.Paused -> CaptureState.Paused
        is CaptureStatus.Error -> CaptureState.Error(status.reason, status.message)
    },
    bufferedMillis = bufferedMillis,
    capacityMinutes = capacityMinutes,
    saveState = saveState,
    forwardRecordingState = forwardRecordingState,
    enginePending = enginePending,
)

@Preview(showBackground = true, name = "Idle")
@Composable
private fun DashboardScreenIdlePreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Idle), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Recording")
@Composable
private fun DashboardScreenRecordingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Recording, bufferedMillis = 12 * 60_000L + 34_000L), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Paused")
@Composable
private fun DashboardScreenPausedPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Paused, bufferedMillis = 8 * 60_000L), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Error - AudioRecord init failed")
@Composable
private fun DashboardScreenErrorPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Error(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, "AudioRecord.state = 0"),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Error - Buffer allocation failed")
@Composable
private fun DashboardScreenErrorBufferAllocationPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Error(
                    CaptureErrorReason.BUFFER_ALLOCATION_FAILED,
                    "Failed to allocate 57600000-byte ring buffer: OutOfMemoryError",
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Engine switch starting (pending)")
@Composable
private fun DashboardScreenEngineStartingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Idle, enginePending = true), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Engine switch stopping (pending)")
@Composable
private fun DashboardScreenEngineStoppingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(CaptureStatus.Recording, bufferedMillis = 5 * 60_000L, enginePending = true),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Buffer full")
@Composable
private fun DashboardScreenBufferFullPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(CaptureStatus.Recording, bufferedMillis = 30 * 60_000L),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Forward recording active")
@Composable
private fun DashboardScreenForwardRecordingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 15 * 60_000L,
                forwardRecordingState = ForwardRecordingUiState.Recording(
                    displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
                    elapsedMillis = 74_000L,
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save exporting")
@Composable
private fun DashboardScreenSaveExportingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Exporting,
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save success")
@Composable
private fun DashboardScreenSaveSuccessPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Success("blackbox_2026-08-21_10-15-00_30min.m4a"),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save error")
@Composable
private fun DashboardScreenSaveErrorPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Error(
                    reason = ExportFailureReason.SINK_OPEN_FAILED,
                    message = "MediaStore insert rejected",
                    timestampMillis = 1_755_000_000_000L,
                    bufferedMillis = 30 * 60_000L,
                    capacityMillis = 30 * 60_000L,
                    qualityPreset = QualityPreset.DEFAULT,
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

/** Test tag for the continuous recording engine switch control. */
const val ENGINE_SWITCH_TEST_TAG = "dashboard_engine_switch"

/** Test tag for the "Save recent audio" primary CTA button. */
const val SAVE_BUTTON_TEST_TAG = "dashboard_save_button"

/** Test tag for the start continuous recording button. */
const val FORWARD_START_BUTTON_TEST_TAG = "dashboard_forward_start_button"

/** Test tag for the stop continuous recording button. */
const val FORWARD_STOP_BUTTON_TEST_TAG = "dashboard_forward_stop_button"

/** Test tag for the forward recording elapsed clock display. */
const val FORWARD_ELAPSED_TEST_TAG = "dashboard_forward_elapsed"

/** Parses start timestamp from standard filename format: blackbox_yyyy-MM-dd_HH-mm-ss_... */
fun parseTimestampFromFilename(filename: String): Long? {
    return try {
        val parts = filename.removePrefix("blackbox_").split("_")
        if (parts.size >= 2) {
            val datePart = parts[0]
            val timePart = parts[1]
            val format = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            format.parse("${datePart}_${timePart}")?.time
        } else null
    } catch (_: Exception) {
        null
    }
}

