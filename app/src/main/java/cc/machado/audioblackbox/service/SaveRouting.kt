package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.export.ForwardRecordingState

/** Shared ACTION_SAVE dispatch decision for dashboard and notification. */
internal fun saveUsesCheckpoint(state: ForwardRecordingState): Boolean =
    state is ForwardRecordingState.Recording
