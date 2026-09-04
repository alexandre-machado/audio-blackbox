package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.ui.theme.RemoveBeforeFlightDragGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for "pull the REMOVE BEFORE FLIGHT tag off to arm the recorder" (issue
 * #284), the dashboard's second route into `onToggleEngine()`.
 *
 * Oracle: every assertion here fails if [RemoveBeforeFlightDragGate] -- the production object the
 * tag's `pointerInput` block drives, and the only thing that decides whether `onToggleEngine()`
 * runs -- stops enforcing one of its four rules: distance is measured in both axes at once, a
 * short pull does not arm, a long pull arms exactly once, and a disabled tag does not move or arm.
 * The counter incremented below is the real callback the dashboard hands the gate, not a
 * re-derivation of the gate's own arithmetic.
 *
 * It lives in the dashboard test package, next to the other tests of this screen's behaviour,
 * because the behaviour under test is the dashboard's (arming the recorder); the gate itself sits
 * in `ui.theme` next to the tag composable it belongs to.
 *
 * What this tier cannot see, stated rather than implied: it does not prove the composable actually
 * routes touch events into this gate, nor that the spring-back, the entry animation or the
 * animations-off path render correctly. Those are Compose-runtime questions, and this repository
 * has no Robolectric on the JVM tier (see AGENTS.md §6); the wiring is a handful of lines in
 * `RemoveBeforeFlightTag` and was checked by reading, not by a test.
 */
class RemoveBeforeFlightDragTest {

    private companion object {
        /** Stands in for `RBF_PULL_THRESHOLD` converted at some density. */
        const val THRESHOLD_PX = 200f
    }

    private var armCount = 0

    private fun gate(enabled: Boolean = true) =
        RemoveBeforeFlightDragGate(THRESHOLD_PX) { armCount++ }.also { it.enabled = enabled }

    @Test
    fun `a pull that stops short of the threshold does not arm the recorder`() {
        val gate = gate()

        // 3-4-5 triangle: 150px of travel against a 200px threshold.
        gate.drag(90f, 120f)

        assertEquals(150f, gate.distance, 0.01f)
        assertEquals(RemoveBeforeFlightDragGate.Release.SPRING_BACK, gate.release())
        assertEquals("A short pull must not arm the recorder", 0, armCount)
        assertFalse(gate.pulled)
    }

    @Test
    fun `a short pull leaves the tag back at its resting position`() {
        val gate = gate()

        gate.drag(-40f, 12f)
        gate.release()

        assertEquals(0f, gate.offsetX, 0f)
        assertEquals(0f, gate.offsetY, 0f)
    }

    @Test
    fun `a pull past the threshold arms the recorder exactly once`() {
        val gate = gate()

        gate.drag(180f, 0f)
        gate.drag(40f, 0f)

        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals(1, armCount)

        // The tag is hidden by the state change this very call causes, but the gesture detector
        // and the recomposition are not the same frame. A second release in that window -- or any
        // further dragging -- must not toggle the engine again.
        assertFalse(gate.drag(300f, 300f))
        assertEquals(RemoveBeforeFlightDragGate.Release.INERT, gate.release())
        assertEquals("The recorder must be armed once per pull, not once per release", 1, armCount)
    }

    @Test
    fun `the tag comes off when pulled straight up, not only sideways`() {
        val gate = gate()

        gate.drag(0f, -220f)

        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals(1, armCount)
    }

    @Test
    fun `a diagonal pull counts its true distance, not one axis of it`() {
        val gate = gate()

        // Neither axis reaches 200px on its own; together they travel ~212px.
        gate.drag(150f, 150f)

        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals(1, armCount)
    }

    @Test
    fun `a disabled tag does not move`() {
        val gate = gate(enabled = false)

        assertFalse("A disabled tag must not follow the finger", gate.drag(400f, 400f))
        assertEquals(0f, gate.offsetX, 0f)
        assertEquals(0f, gate.offsetY, 0f)
    }

    @Test
    fun `a disabled tag never arms the recorder, however far it is dragged`() {
        val gate = gate(enabled = false)

        gate.drag(500f, 500f)

        assertEquals(RemoveBeforeFlightDragGate.Release.INERT, gate.release())
        assertEquals("The drag must respect the same gate as the engine switch", 0, armCount)
        assertFalse(gate.pulled)
    }

    @Test
    fun `a tag disabled mid-drag does not arm when the finger is lifted`() {
        val gate = gate()

        gate.drag(0f, 400f)
        // e.g. the microphone permission is revoked from the notification shade while the user is
        // still holding the tag; the Switch would go disabled in the same recomposition.
        gate.enabled = false

        assertEquals(RemoveBeforeFlightDragGate.Release.INERT, gate.release())
        assertEquals(0, armCount)
    }

    @Test
    fun `a cancelled gesture puts the tag back without arming`() {
        val gate = gate()

        gate.drag(0f, 400f)

        assertEquals(RemoveBeforeFlightDragGate.Release.SPRING_BACK, gate.cancel())
        assertEquals("Losing the pointer must not arm the recorder", 0, armCount)
        assertEquals(0f, gate.offsetY, 0f)
    }

    // ---- the gesture going inert and live again while the tag is still on the panel ----
    //
    // PR #320 review (`@rev` findings 1 and 2). The original latch never cleared, justified by "the
    // state change removes the tag from the composition" -- true only when the start succeeds.
    // `mapEngineSwitchState` keeps `checked = false` for both Idle and Error, so a start that fails,
    // or a `toggleEngine` timeout backstop that clears `pending` with the status still Idle, leaves
    // the same remembered gate composed with `pulled` stuck on.

    @Test
    fun `a pull that did not take gives the drag route back`() {
        val gate = gate()

        gate.drag(0f, 250f)
        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals(1, armCount)

        // The recorder never started (Error, or the pending window closed with it still idle), so
        // the tag is still composed and the gesture goes live again.
        assertTrue("a spent gate must re-arm once its pull is known not to have taken", gate.rearm())
        assertFalse(gate.pulled)
        assertEquals("re-arming must also bring the tag home", 0f, gate.offsetX, 0f)
        assertEquals(0f, gate.offsetY, 0f)

        // ...and the route is alive, rather than dead for the rest of this composition.
        assertTrue(gate.drag(0f, 250f))
        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals("the second pull must reach the recorder too", 2, armCount)
    }

    @Test
    fun `re-arming a gate that never armed anything reports that it had nothing to undo`() {
        val gate = gate()

        gate.drag(10f, 10f)
        gate.release()

        assertFalse(
            "nothing was pulled, so there is nothing to refit -- the tag must not play a return " +
                "animation every time the switch's enabled gate flickers",
            gate.rearm(),
        )
    }

    @Test
    fun `a tag stranded mid-drag is brought home when the gesture goes inert`() {
        val gate = gate()

        gate.drag(0f, 400f)
        // The switch's enabled gate closes mid-gesture, which uninstalls the drag detector, so no
        // onDragEnd or onDragCancel will ever arrive for this gesture.
        gate.enabled = false

        assertTrue("a tag left mid-drag must be reported as stranded", gate.standDown())
        assertEquals(0f, gate.offsetX, 0f)
        assertEquals("the tag must not be left hanging where the finger dropped it", 0f, gate.offsetY, 0f)
        assertEquals("standing down must never arm the recorder", 0, armCount)
    }

    @Test
    fun `a tag that never moved reports nothing to stand down from`() {
        val gate = gate()

        assertFalse(gate.standDown())
    }

    @Test
    fun `a tag that was pulled off is left where it is, to carry on off the panel`() {
        val gate = gate()

        gate.drag(300f, 0f)
        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())

        // The gesture goes inert because the pull worked (the switch is disabled while the toggle
        // is pending). The tag is not stranded here -- it is leaving, and its offset is where the
        // exit motion continues from.
        assertFalse("a pulled tag is on its way out, not stranded", gate.standDown())
        assertEquals(300f, gate.offsetX, 0f)
        assertTrue(gate.pulled)
    }

    @Test
    fun `travel accumulates across the whole gesture rather than per event`() {
        val gate = gate()

        repeat(20) { assertTrue(gate.drag(0f, 11f)) }

        assertEquals(220f, gate.distance, 0.01f)
        assertEquals(RemoveBeforeFlightDragGate.Release.PULL, gate.release())
        assertEquals(1, armCount)
    }
}
