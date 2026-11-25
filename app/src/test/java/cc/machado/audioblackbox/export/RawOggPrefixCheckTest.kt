package cc.machado.audioblackbox.export

import org.junit.Assert.*
import org.junit.Test

class RawOggPrefixCheckTest {
    // Oracle: the device gate must reject each new failure even on a one-page cut.
    @Test fun onlyEmptySuccessfulDecodeIsExempt() {
        val reference = byteArrayOf(1, 2, 3, 4)
        val ok = Result.success(Unit)
        val empty = Result.success(byteArrayOf())
        val exception = Result.failure<ByteArray>(IllegalStateException("decoder"))
        val wrong = Result.success(byteArrayOf(9, 9))
        assertTrue(RawOggPrefixCheck.isKnownEmpty(1, empty))
        assertFalse(RawOggPrefixCheck.isKnownEmpty(1, exception))
        assertFalse(RawOggPrefixCheck.isKnownEmpty(1, wrong))
        assertFalse(RawOggPrefixCheck.isKnownEmpty(2, empty))
        assertTrue(RawOggPrefixCheck.failures(1, empty, ok, reference, reference).isEmpty())
        assertTrue(RawOggPrefixCheck.failures(1, exception, ok, reference, reference).any { it.startsWith("decode:") })
        assertTrue(RawOggPrefixCheck.failures(1, wrong, ok, reference, null).any { it.contains("differs") })
        assertTrue(RawOggPrefixCheck.failures(1, empty, Result.failure(IllegalStateException("player")),
            reference, reference).any { it.startsWith("MediaPlayer:") })
        assertTrue(RawOggPrefixCheck.failures(2, empty, ok, reference, reference).isNotEmpty())
        assertTrue(RawOggPrefixCheck.failures(1, Result.success(reference), ok, reference, reference).isEmpty())
    }
}
