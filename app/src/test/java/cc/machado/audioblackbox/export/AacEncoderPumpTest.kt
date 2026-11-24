package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaFormat
import cc.machado.audioblackbox.audio.AudioConfig
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class AacEncoderPumpTest {
    /** Oracle: real pump dispatch skips nonempty codec config but delivers ordinary and
     * EOS-bearing audio, preserving buffer bounds and releasing every dequeued buffer. */
    @Test fun codecConfigIsSkippedButAudioAndEosAreDelivered() {
        val codec = mock<MediaCodec>()
        val format = mock<MediaFormat>()
        val samples = mutableListOf<List<Byte>>()
        val endFlags = mutableListOf<Boolean>()
        var ended = false
        Mockito.mockStatic(MediaFormat::class.java).use { formats ->
            formats.`when`<MediaFormat> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1)
            }.thenReturn(format)
            Mockito.mockStatic(MediaCodec::class.java).use { codecs ->
                codecs.`when`<MediaCodec> {
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                }.thenReturn(codec)
                var index = 0
                whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer { call ->
                    val info = call.getArgument<MediaCodec.BufferInfo>(0)
                    info.offset = 1
                    info.size = 2
                    info.flags = when (index) {
                        0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        2 -> MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        else -> 0
                    }
                    index++
                }
                whenever(codec.getOutputBuffer(any())).thenAnswer { call ->
                    val i = call.getArgument<Int>(0)
                    ByteBuffer.wrap(byteArrayOf(99, i.toByte(), (i + 10).toByte(), 99))
                }
                AacEncoderPump(AudioConfig(sampleRateHz = 16000, channelCount = 1), 64000,
                    onFormat = {},
                    onSample = { buffer, _, eos ->
                        samples += ByteArray(buffer.remaining()).also { buffer.get(it) }.toList()
                        endFlags += eos
                    },
                    onEnd = { ended = true },
                ).use { it.write(ByteArray(0), 0, 0) }
                assertEquals(listOf(listOf<Byte>(1, 11), listOf<Byte>(2, 12)), samples)
                assertEquals(listOf(false, true), endFlags)
                assertTrue(ended)
                for (i in 0..2) verify(codec).releaseOutputBuffer(i, false)
            }
        }
    }
}
