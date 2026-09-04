package cc.machado.audioblackbox.ui.theme

import kotlin.math.hypot

/**
 * The decision logic behind "pull the REMOVE BEFORE FLIGHT tag off the panel to arm the recorder"
 * (issue [#284](https://github.com/alexandre-machado/audio-blackbox/issues/284)).
 *
 * It lives outside the composable on purpose. The gesture arms the recorder -- the single most
 * consequential action on the dashboard -- and this repository has no Robolectric on the JVM tier,
 * so a rule expressed only inside a `pointerInput` block would be reachable only by the CI-only
 * instrumented tier. Everything that decides *whether* `onToggleEngine()` fires is therefore plain
 * Kotlin here, and [RemoveBeforeFlightTag] owns only the part that genuinely needs Compose:
 * turning [offsetX]/[offsetY] into pixels on screen and animating the spring-back.
 *
 * The rules, all of which have a test in `RemoveBeforeFlightDragTest`:
 * - Movement is free in both axes; the threshold is the straight-line [distance] pulled, so the
 *   tag comes off in *any* direction rather than along one axis.
 * - Released below the threshold: nothing arms, and the tag goes back to its origin.
 * - Released at or above the threshold: exactly one pull is ever reported. [pulled] latches, so a
 *   second release (or any further drag) in the frames before the recorder's state actually flips
 *   and removes the tag from the composition cannot toggle the engine a second time.
 * - [enabled] mirrors the engine `Switch`'s own enabled/allowed gate. While it is `false` the tag
 *   does not even move, which is what makes "the gesture is inert" observable rather than a claim
 *   about a callback that was never going to be reachable anyway.
 *
 * Not thread-safe, and does not need to be: it is touched only from the UI thread, by one
 * gesture detector.
 *
 * The arm action is a constructor argument rather than something the caller runs off the returned
 * [Release] on purpose: "fires exactly once, and never while disabled" is a property of the
 * callback, so the callback has to be inside the thing under test. A gate that only returned an
 * enum would leave the interesting half of the rule in the `when` at the call site, where no JVM
 * test can reach it.
 *
 * @param thresholdPx how far the tag must travel, in pixels, before a release counts as a pull.
 * @param onPull run once, at the moment the tag comes off. On the dashboard this is the same
 *   `onToggleEngine()` the engine `Switch` calls.
 */
class RemoveBeforeFlightDragGate(
    private val thresholdPx: Float,
    private val onPull: () -> Unit,
) {

    /**
     * Whether the gesture may do anything at all. Mirrors `engineSwitch.enabled` plus "the
     * recorder is currently off", since pulling the tag can only ever arm, never disarm.
     */
    var enabled: Boolean = true

    var offsetX: Float = 0f
        private set

    var offsetY: Float = 0f
        private set

    /** Latches on the one release that armed the recorder. Never clears. */
    var pulled: Boolean = false
        private set

    /** Straight-line distance from the tag's resting position, in pixels. */
    val distance: Float
        get() = hypot(offsetX, offsetY)

    /**
     * Accumulates one drag delta. Returns whether the tag actually moved, so the caller knows
     * whether to consume the pointer change and repaint.
     */
    fun drag(deltaX: Float, deltaY: Float): Boolean {
        if (!enabled || pulled) return false
        offsetX += deltaX
        offsetY += deltaY
        return true
    }

    /** The user lifted their finger. Runs `onPull` if, and only if, this is the pull. */
    fun release(): Release {
        if (!enabled || pulled) return Release.INERT
        if (distance >= thresholdPx) {
            pulled = true
            onPull()
            return Release.PULL
        }
        recentre()
        return Release.SPRING_BACK
    }

    /** The gesture was cancelled (another pointer won it, the window lost the touch, ...). */
    fun cancel(): Release {
        if (!enabled || pulled) return Release.INERT
        recentre()
        return Release.SPRING_BACK
    }

    private fun recentre() {
        offsetX = 0f
        offsetY = 0f
    }

    enum class Release {
        /** Far enough: arm the recorder, exactly once. */
        PULL,

        /** Not far enough: the tag is still attached, snap it home. */
        SPRING_BACK,

        /** The gesture was never live (disabled, or already spent). Do nothing. */
        INERT,
    }
}
