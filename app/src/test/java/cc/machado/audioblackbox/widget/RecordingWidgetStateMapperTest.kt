package cc.machado.audioblackbox.widget

import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [RecordingWidgetStateMapper] (issue #275).
 *
 * Oracle for every test below: [RecordingWidgetStateMapper.map] must derive its entire output --
 * status text, annunciator color, action label, and which action ([RecorderService.ACTION_START]
 * vs. `ACTION_STOP]) the button's `PendingIntent` will carry -- solely from the [CaptureState]
 * value passed in, with a fixed, exhaustive (`sealed interface`, no `else`) mapping. No test here
 * touches `RemoteViews`/`Context`/`AppWidgetManager`: those are real Android framework classes
 * this repo has no Robolectric shim for (see `PeriodicNotificationRefresherTest`'s doc for the
 * same constraint elsewhere), so [RecordingWidgetRenderer]'s mechanical translation of this
 * model into `RemoteViews` calls is a Tier 2 (physical device) concern, not covered here -- stated
 * plainly rather than implied.
 *
 * This suite also cannot, and does not claim to, cover the OS-level while-in-use eligibility
 * decision (whether a real widget tap's `startForegroundService()` call is actually let through)
 * -- that is a device-only concern per `AGENTS.md` §6 and the PR's own device-evidence section.
 * What this suite covers is the exact failure class that made the removed Quick Settings tile
 * unusable: rendering a state that does not match reality.
 */
class RecordingWidgetStateMapperTest {

    @Test
    fun `Recording maps to the green recording state with a Stop action`() {
        val model = RecordingWidgetStateMapper.map(CaptureState.Recording)

        assertEquals(R.string.widget_status_recording, model.statusTextRes)
        assertEquals(WidgetAnnunciator.OK_GREEN, model.annunciator)
        assertEquals(R.string.widget_action_stop, model.actionButtonLabelRes)
        assertTrue("Recording must offer Stop, never Start", model.actionIsStop)
    }

    @Test
    fun `Paused maps to the amber caution state with a Stop action`() {
        val model = RecordingWidgetStateMapper.map(CaptureState.Paused)

        assertEquals(R.string.widget_status_paused, model.statusTextRes)
        assertEquals(WidgetAnnunciator.CAUTION_AMBER, model.annunciator)
        assertTrue("Paused must still offer Stop -- the mic is open", model.actionIsStop)
    }

    @Test
    fun `Error maps to the red error state with a Start action, never a stale Stop`() {
        val model = RecordingWidgetStateMapper.map(
            CaptureState.Error(CaptureErrorReason.UNSUPPORTED_CONFIG, "boom"),
        )

        assertEquals(R.string.widget_status_error, model.statusTextRes)
        assertEquals(WidgetAnnunciator.WARNING_RED, model.annunciator)
        assertEquals(R.string.widget_action_start, model.actionButtonLabelRes)
        assertFalse(
            "A refused/failed start must never render as if capture is running",
            model.actionIsStop,
        )
    }

    /**
     * This is the exact defect that made the removed Quick Settings tile unusable (issue
     * #267/#273): [cc.machado.audioblackbox.service.RecorderService]'s companion object resets
     * `captureState` to [CaptureState.Idle] on every fresh process (a crash, an OS reclaim, a
     * reboot). Feeding that literal reset value through this mapper must render the honest
     * "not recording" widget -- an Idle-mapped model with a Start action -- never a state that
     * looks like a leftover "on" from before the process died. [RecordingWidgetUpdater]'s doc
     * covers the other half of this fix (making sure this mapping actually gets repainted
     * promptly after a process restart); this test covers that the mapping itself is correct
     * for the value a fresh process actually holds.
     */
    @Test
    fun `Idle (the fresh-process reset value) maps to the dim idle state with a Start action`() {
        val model = RecordingWidgetStateMapper.map(CaptureState.Idle)

        assertEquals(R.string.widget_status_idle, model.statusTextRes)
        assertEquals(WidgetAnnunciator.IDLE_DIM, model.annunciator)
        assertEquals(R.string.widget_action_start, model.actionButtonLabelRes)
        assertFalse(
            "A process-death reset to Idle must never render as an already-running recording",
            model.actionIsStop,
        )
    }

    /**
     * Issue #279 acceptance criterion 4: the widget's proactive-announcement gap (recorded as a
     * named exception in `AGENTS.md` §5, since `RemoteViews` has no live-region equivalent) must
     * not be allowed to erode what already *does* work -- the per-state, state-derived
     * `contentDescription`s on the widget root and its action button, which remain the only
     * affordance a TalkBack user has for this widget (see [RecordingWidgetRenderer]). Oracle: each
     * of the four [CaptureState] values must map to its own distinct root and action-button
     * description resource, so a regression that collapsed two states onto the same description
     * (or silently dropped one) fails here on the JVM, without needing a device.
     */
    @Test
    fun `every state maps to a distinct, state-derived content description pair`() {
        val states = listOf(
            CaptureState.Recording,
            CaptureState.Paused,
            CaptureState.Error(CaptureErrorReason.UNSUPPORTED_CONFIG, "boom"),
            CaptureState.Idle,
        )
        val models = states.map { RecordingWidgetStateMapper.map(it) }

        assertEquals(
            R.string.widget_content_description_recording,
            models[0].rootContentDescriptionRes,
        )
        assertEquals(
            R.string.widget_content_description_paused,
            models[1].rootContentDescriptionRes,
        )
        assertEquals(
            R.string.widget_content_description_error,
            models[2].rootContentDescriptionRes,
        )
        assertEquals(
            R.string.widget_content_description_idle,
            models[3].rootContentDescriptionRes,
        )

        val rootDescriptions = models.map { it.rootContentDescriptionRes }
        assertEquals(
            "Recording/Paused/Error/Idle must never share a root contentDescription -- a " +
                "TalkBack user navigating to the widget must be able to tell them apart",
            rootDescriptions.toSet().size,
            rootDescriptions.size,
        )

        val actionDescriptions = models.map { it.actionButtonDescriptionRes }
        assertEquals(
            "Recording and Paused both offer Stop, and Error/Idle both offer Start, so the " +
                "action-button description set collapses to exactly two distinct values, not four",
            2,
            actionDescriptions.toSet().size,
        )
    }

    @Test
    fun `every state maps to a distinct annunciator color`() {
        val annunciators = listOf(
            CaptureState.Recording,
            CaptureState.Paused,
            CaptureState.Error(CaptureErrorReason.UNSUPPORTED_CONFIG, "boom"),
            CaptureState.Idle,
        ).map { RecordingWidgetStateMapper.map(it).annunciator }

        assertEquals(
            "Recording/Paused/Error/Idle must never share an annunciator color -- " +
                "collapsing any two would make the widget unable to distinguish them visually",
            annunciators.toSet().size,
            annunciators.size,
        )
    }
}
