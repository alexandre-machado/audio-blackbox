package cc.machado.audioblackbox.export

/** Shared by device qualification and JVM negative controls; never shipped in the app. */
internal object RawOggPrefixCheck {
    fun isKnownEmpty(pages: Int, decoded: Result<ByteArray>): Boolean =
        pages == 1 && decoded.getOrNull()?.isEmpty() == true

    fun failures(
        pages: Int, decoded: Result<ByteArray>, player: Result<Unit>,
        reference: ByteArray, completePcm: ByteArray?,
    ): List<String> = buildList {
        if (pages < 1) return@buildList
        val pcm = decoded.getOrNull()
        if (!isKnownEmpty(pages, decoded)) {
            if (pcm == null || pcm.isEmpty()) add("decode: ${decoded.exceptionOrNull()}")
            else {
                if (completePcm != null && pcm.size < completePcm.size) add("lost PCM from a complete page")
                if (pcm.size > reference.size || pcm.indices.any { pcm[it] != reference[it] }) {
                    add("decoded PCM differs from full reference prefix")
                }
            }
        }
        if (player.isFailure) add("MediaPlayer: ${player.exceptionOrNull()}")
    }
}
