package cc.machado.audioblackbox.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures `AudioRecord`'s real internal buffer headroom (issue #22), expressed in milliseconds
 * of audio, at each config [RingBufferSnapshotLockBenchmarkTest] benchmarks `snapshot()` at.
 *
 * `AudioRecord.getMinBufferSize` needs the Android framework's audio HAL, so this lives in the
 * instrumented tier rather than the JVM unit-test tier -- it does not need `RECORD_AUDIO` (it is
 * a static sizing query, not an open recording session), so no permission grant is needed here.
 *
 * `internalBufferSize = minBufferSize * 3` mirrors [AudioCaptureEngine]'s
 * `createAudioRecord` exactly (see that function) -- if that multiplier ever changes, this test's
 * headroom number silently drifts from the real one, so keep them in sync.
 *
 * Results are logged (`Log.i`, tag [TAG]) rather than asserted against a threshold, for the same
 * flake-avoidance reason [RingBufferSnapshotLockBenchmarkTest] gives in its class doc: a real
 * headroom number from a booted emulator, read by a human out of the CI log
 * (`scripts/ci/run-instrumented-tier.sh` dumps this tag's logcat output after phase 1), not an
 * assertion that can flake on a differently-provisioned runner.
 *
 * ## Measurement platform caveat
 * This runs on the CI instrumented tier's emulator: API 30, `google_apis`, x86_64, on a GitHub
 * Actions `ubuntu-latest` host -- i.e. an x86 audio HAL under KVM, not the repo owner's Samsung
 * S25's real ARM audio driver. `AudioRecord.getMinBufferSize`'s result is driver/HAL-dependent,
 * so this number does not necessarily transfer to the S25; see issue #22's comment thread.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecordHeadroomInstrumentedTest {

    private data class Config(val label: String, val sampleRateHz: Int, val channelCount: Int)

    @Test
    fun logAudioRecordHeadroomForEachConfig() {
        val configs = listOf(
            Config(
                label = "16kHz/mono (REAL: default sample format, max retention offered by the UI)",
                sampleRateHz = 16_000,
                channelCount = 1,
            ),
            Config(
                label = "44.1kHz/stereo (HYPOTHETICAL: no UI path sets this today)",
                sampleRateHz = 44_100,
                channelCount = 2,
            ),
        )

        for (cfg in configs) {
            val channelConfig = when (cfg.channelCount) {
                1 -> AudioFormat.CHANNEL_IN_MONO
                2 -> AudioFormat.CHANNEL_IN_STEREO
                else -> error("unsupported channelCount in test config: ${cfg.channelCount}")
            }
            val minBufferSize = AudioRecord.getMinBufferSize(
                cfg.sampleRateHz,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBufferSize > 0) {
                "AudioRecord.getMinBufferSize returned $minBufferSize (<=0) for " +
                    "${cfg.label} on this emulator -- cannot derive headroom."
            }

            val internalBufferSize = minBufferSize * INTERNAL_BUFFER_MULTIPLIER
            val bytesPerFrame = BYTES_PER_SAMPLE_PCM16 * cfg.channelCount
            val bytesPerSecond = cfg.sampleRateHz * bytesPerFrame
            val headroomMillis = internalBufferSize.toLong() * MILLIS_PER_SECOND / bytesPerSecond

            Log.i(
                TAG,
                "config=${cfg.label} minBufferSize=$minBufferSize " +
                    "internalBufferSize=$internalBufferSize bytesPerSecond=$bytesPerSecond " +
                    "headroomMillis=$headroomMillis",
            )
        }
    }

    private companion object {
        const val TAG = "HeadroomBenchmark"
        const val BYTES_PER_SAMPLE_PCM16 = 2
        const val MILLIS_PER_SECOND = 1000L
        // Mirrors AudioCaptureEngine.createAudioRecord's internalBufferSize = minBufferSize * 3.
        const val INTERNAL_BUFFER_MULTIPLIER = 3
    }
}
