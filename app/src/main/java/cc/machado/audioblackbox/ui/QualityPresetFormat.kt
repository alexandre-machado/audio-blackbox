package cc.machado.audioblackbox.ui

import androidx.annotation.StringRes
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.QualityPreset

/**
 * The single source of truth for a [QualityPreset]'s user-facing spec label (issue #334).
 *
 * Before this, the fact "which sample rate/channel count is active" was asserted in three places
 * that could (and did) disagree: the dashboard's `AvionicsTag` rendered a fixed
 * `dashboard_engine_sample_rate` string ("16.0 kHz PCM") that never changed with the selected
 * preset, `SettingsScreen`'s header tag computed its own hardcoded `"16 kHz · MONO"`-shaped
 * literal in a `when`, and the `settings_preset_*_specs` string resources sitting right next to
 * that `when` expressed the same fact a third time without ever being read by it. All three now
 * resolve through this one mapping onto the existing, already-localized
 * `settings_preset_*_specs` resources.
 *
 * **Label shape decision**: this keeps Settings' `"<rate> · <channel word>"` shape (e.g.
 * `"16 kHz · Mono"`), not the dashboard's old `"16.0 kHz PCM"` shape. Sample rate and channel
 * count are the two [QualityPreset] properties that actually vary across [QualityPreset.entries]
 * and are what a user who just switched presets needs confirmed; codec does not vary (see below),
 * so it carries no distinguishing information and is left out of the short tag.
 *
 * **Why the codec half ("PCM") is not part of this label**: it is not a property of
 * [QualityPreset] at all. `AudioCaptureEngine` requests `AudioFormat.ENCODING_PCM_16BIT`
 * unconditionally for every preset -- there is no per-preset codec selection anywhere in the
 * capture path -- so "PCM" is invariant by construction, not a value that could drift out of sync
 * with the tag the way the sample rate did. Documented here rather than displayed, since a
 * constant carries no information a user switching between three PCM-only presets needs repeated
 * back at them.
 *
 * Exhaustive `when` with no `else` branch: adding a new [QualityPreset] constant fails this file
 * to compile until it is given a label here too.
 */
@StringRes
fun QualityPreset.specLabelRes(): Int = when (this) {
    QualityPreset.VOICE -> R.string.settings_preset_voice_specs
    QualityPreset.BALANCED -> R.string.settings_preset_balanced_specs
    QualityPreset.HIGH_FIDELITY -> R.string.settings_preset_high_fidelity_specs
}
