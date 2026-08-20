package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.GoertzelDetector
import cc.machado.audioblackbox.audio.ToneGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAV round-trip with a known signal (issue #5 mandatory test, using the instrument merged
 * specifically for this measurement -- see issue #21). Generates a known tone, runs it through
 * [WavWriter], decodes the written bytes back out (header fields + payload, exactly as a real
 * player would read them), and asserts with [GoertzelDetector] that the decoded signal is still
 * that tone at the declared sample rate and channel count.
 *
 * This catches defects a byte-pattern test cannot: wrong endianness, a wrong sample-rate field
 * in the header, or wrong channel interleaving all still produce a file that *opens* -- it just
 * plays at the wrong pitch/speed, or the decoded samples are garbage. Each assertion below reads
 * the header field a real player reads (not the [AudioConfig] object used to write it) and each
 * one, if flipped, moves the detected frequency away from the injected tone -- so a wrong field
 * fails this test, not just a hardcoded byte comparison.
 */
class WavRoundTripTest {

    @Test
    fun `1kHz tone at 16kHz mono round-trips through WavWriter`() {
        assertRoundTrip(sampleRateHz = 16_000, channelCount = 1, toneHz = 1000.0)
    }

    @Test
    fun `1kHz tone at 44100Hz stereo round-trips through WavWriter`() {
        assertRoundTrip(sampleRateHz = 44_100, channelCount = 2, toneHz = 1000.0)
    }

    private fun assertRoundTrip(sampleRateHz: Int, channelCount: Int, toneHz: Double) {
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = channelCount)
        val originalPcm = ToneGenerator.tone(
            frequencyHz = toneHz,
            sampleRateHz = sampleRateHz,
            durationMillis = 500,
            channelCount = channelCount,
        )

        val encoded = ByteArrayOutputStream()
        WavWriter.write(encoded, config, originalPcm)
        val fileBytes = encoded.toByteArray()

        // Decode exactly as a real player would: read the declared fields from the header, not
        // from `config` -- if WavWriter wrote the wrong sample rate/channel count/endianness,
        // this decode step (and the detector below) is what would notice.
        val header = ByteBuffer.wrap(fileBytes, 0, WavWriter.HEADER_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val declaredChannels = header.getShort(22).toInt()
        val declaredSampleRate = header.getInt(24)
        val declaredDataSize = header.getInt(40)

        val decodedPcm = ByteArrayInputStream(fileBytes, WavWriter.HEADER_SIZE_BYTES, declaredDataSize).readBytes()
        assertEquals(originalPcm.size, decodedPcm.size)

        val energyAtTone = GoertzelDetector.energyAt(decodedPcm, toneHz, declaredSampleRate, declaredChannels)
        val energyAtHalf = GoertzelDetector.energyAt(decodedPcm, toneHz / 2, declaredSampleRate, declaredChannels)
        val energyAtDouble = GoertzelDetector.energyAt(decodedPcm, toneHz * 2, declaredSampleRate, declaredChannels)

        // The decoded signal is dominated by the injected tone, not its neighbours -- if the
        // header's sample rate were wrong, the same byte offsets would be reinterpreted at the
        // wrong rate and this detector call (which trusts `declaredSampleRate`) would find the
        // energy at the wrong frequency instead, failing this assertion. Likewise, wrong channel
        // interleaving (e.g. treating stereo bytes as mono) would corrupt every other sample,
        // collapsing the energy at the true tone frequency.
        assertTrue("expected strong energy at ${toneHz}Hz, got $energyAtTone", energyAtTone > 1000.0)
        assertTrue("expected weak energy at ${toneHz / 2}Hz, got $energyAtHalf", energyAtHalf < energyAtTone / 10)
        assertTrue("expected weak energy at ${toneHz * 2}Hz, got $energyAtDouble", energyAtDouble < energyAtTone / 10)
    }
}
