package cc.machado.audioblackbox.ui.gallery

import android.net.Uri
import cc.machado.audioblackbox.export.RecordingRow
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Instance-level tests for [GalleryViewModel] -- refresh sourcing from [RecordingsRepository]
 * (never an app-local cache), delegation to [RecordingPlayer], and the delete flow. Uses
 * [FakeRecordingsRepository]/[FakeRecordingPlayer] (never mocks the whole [GalleryViewModel]
 * itself) and [Dispatchers.Unconfined] as the injected IO dispatcher so every `launch`/
 * `withContext` inside the ViewModel completes synchronously up to the next suspension point --
 * no `sleep`, no timing assumption, matching [kotlinx.coroutines.test]'s own recommended pattern
 * already used by [cc.machado.audioblackbox.ui.dashboard.DashboardViewModelDoubleTapTest].
 *
 * [uiState] is built with `SharingStarted.WhileSubscribed` (matching
 * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s own choice, for the same
 * lifecycle-economy reason), so it only actually combines/produces values while something is
 * collecting it -- [subscribe] below starts a [TestScope.backgroundScope] collector (auto-cancelled
 * at the end of [runTest], no explicit teardown needed) before any test reads `.value`.
 */
class GalleryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(name: String, uri: Uri = mock()) = RecordingRow(
        uri = uri,
        displayName = name,
        mimeType = "audio/mp4",
        sizeBytes = 1_000L,
        durationMillis = 60_000L,
        dateAddedMillis = 0L,
    )

    private fun TestScope.subscribe(viewModel: GalleryViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
    }

    @Test
    fun `on init, the list is populated from the repository's real query, not left empty`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.items.size)
    }

    @Test
    fun `a row MediaStore no longer has disappears from the list on the next refresh -- no phantom entry`() = runTest {
        val uri = mock<Uri>()
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a", uri)))
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        assertEquals(1, viewModel.uiState.value.items.size)

        // Simulates the file being removed by something other than this app (a file manager, the
        // OS itself) -- the row is just gone from the next query, nothing routes through delete().
        repository.rows = emptyList()
        viewModel.refresh()
        runCurrent()

        assertTrue(
            "a row MediaStore no longer has must not survive as a phantom list entry",
            viewModel.uiState.value.items.isEmpty(),
        )
    }

    // ---- issue #375 Part A: automatic invalidation, no manual refresh() call from the caller ----

    /** Records the [onChanged] lambda [GalleryViewModel] registers, so the test can fire it
     * directly to simulate a real `ContentObserver`/`MediaStore` notification without any Android
     * framework dependency -- see [RecordingsChangeObserver]'s own doc for why a real
     * `ContentObserver` is the production implementation this fake stands in for. */
    private class FakeRecordingsChangeObserver : RecordingsChangeObserver {
        var onChanged: (() -> Unit)? = null
        var closed = false
            private set

        override fun observe(onChanged: () -> Unit): Closeable {
            this.onChanged = onChanged
            return Closeable { closed = true }
        }

        fun fireChange() = onChanged?.invoke()
    }

    @Test
    fun `firing the change observer re-runs the query on its own, with no manual refresh() call`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val changeObserver = FakeRecordingsChangeObserver()
        val viewModel = GalleryViewModel(
            repository,
            FakeRecordingPlayer(),
            ioDispatcher = Dispatchers.Unconfined,
            changeObserver = changeObserver,
        )
        subscribe(viewModel)
        assertEquals(1, viewModel.uiState.value.items.size)

        // A save completing (or any other app's change) shows up as a second row, with nothing in
        // this test ever calling viewModel.refresh() itself -- only the observer's callback does.
        repository.rows = listOf(
            row("blackbox_2026-01-01_00-00-00_5min.m4a"),
            row("blackbox_2026-01-02_00-00-00_5min.m4a"),
        )
        changeObserver.fireChange()
        runCurrent()

        assertEquals(
            "a change-observer notification must re-run the real query on its own -- this is the " +
                "whole point of issue #375 Part A, not requiring the gallery screen to be reopened",
            2,
            viewModel.uiState.value.items.size,
        )
    }

    @Test
    fun `onCleared closes the change observer's subscription`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val changeObserver = FakeRecordingsChangeObserver()
        val viewModel = GalleryViewModel(
            repository,
            FakeRecordingPlayer(),
            ioDispatcher = Dispatchers.Unconfined,
            changeObserver = changeObserver,
        )
        subscribe(viewModel)

        // ViewModel.onCleared() is protected -- routed through a real ViewModelStore instead,
        // exactly as the Android framework tears a ViewModel down in production.
        val store = androidx.lifecycle.ViewModelStore()
        store.put("gallery", viewModel)
        store.clear()

        assertTrue("a cleared ViewModel must not leak its ContentObserver registration", changeObserver.closed)
    }

    @Test
    fun `onPlayPauseClicked on an idle player starts playback of that item`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording

        viewModel.onPlayPauseClicked(item)

        assertEquals(listOf(item.uri), player.playCalls)
        assertEquals(PlaybackState.Playing(item.uri), player.playback.value)
    }

    @Test
    fun `onPlayPauseClicked on the currently-playing item pauses it instead of restarting it`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onPlayPauseClicked(item)

        viewModel.onPlayPauseClicked(item)

        assertEquals("never a second play() call for the same item", 1, player.playCalls.size)
        assertEquals(PlaybackState.Paused(item.uri), player.playback.value)
    }

    @Test
    fun `onPlayPauseClicked on a paused item resumes it`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onPlayPauseClicked(item) // Playing
        viewModel.onPlayPauseClicked(item) // Paused

        viewModel.onPlayPauseClicked(item) // resume

        assertEquals(1, player.playCalls.size)
        assertEquals(PlaybackState.Playing(item.uri), player.playback.value)
    }

    @Test
    fun `starting playback of a second item while one is playing stops the first, and only the second reports Playing`() = runTest {
        val a = row("blackbox_2026-01-01_00-00-00_5min.m4a")
        val b = row("blackbox_2026-01-02_00-00-00_5min.m4a")
        val repository = FakeRecordingsRepository(listOf(a, b))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val items = viewModel.uiState.value.items.map { it.recording }
        val itemA = items.single { it.uri == a.uri }
        val itemB = items.single { it.uri == b.uri }

        viewModel.onPlayPauseClicked(itemA)
        viewModel.onPlayPauseClicked(itemB)
        runCurrent()

        assertEquals(listOf(a.uri, b.uri), player.playCalls)
        assertEquals(PlaybackState.Playing(b.uri), player.playback.value)
        val state = viewModel.uiState.value
        assertEquals(ItemPlaybackState.Stopped, state.items.single { it.recording.uri == a.uri }.playback)
        assertTrue(state.items.single { it.recording.uri == b.uri }.playback is ItemPlaybackState.Playing)
    }

    @Test
    fun `onDeleteRequested stages the item without touching the repository until confirmed`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording

        viewModel.onDeleteRequested(item)
        runCurrent()

        assertEquals(item, viewModel.uiState.value.pendingDelete)
        assertTrue(repository.deleted.isEmpty())
    }

    @Test
    fun `onDeleteConfirmed deletes via the repository and the list reflects it after refresh`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onDeleteRequested(item)
        runCurrent()

        viewModel.onDeleteConfirmed()
        runCurrent()

        assertEquals(listOf(item.uri), repository.deleted)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertNull(viewModel.uiState.value.pendingDelete)
        assertNull("a successful delete must not show an error", viewModel.uiState.value.deleteError)
    }

    @Test
    fun `onDeleteCancelled clears the staged item without ever calling the repository`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onDeleteRequested(item)
        runCurrent()

        viewModel.onDeleteCancelled()
        runCurrent()

        assertNull(viewModel.uiState.value.pendingDelete)
        assertTrue(repository.deleted.isEmpty())
        assertEquals(1, viewModel.uiState.value.items.size) // never actually deleted
    }

    // ---- PR #61 review: a delete that fails (row not owned by this app -- issue #59) must be a
    // real, visible failure, never a silent optimistic removal of a file still on disk ----

    @Test
    fun `onDeleteConfirmed surfaces a visible error and keeps the item when the repository reports failure`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        repository.deleteSucceeds = false
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onDeleteRequested(item)
        runCurrent()

        viewModel.onDeleteConfirmed()
        runCurrent()

        assertEquals(
            "a row this app couldn't actually delete must still be in the list -- it is still on disk",
            1,
            viewModel.uiState.value.items.size,
        )
        assertEquals(item, viewModel.uiState.value.deleteError)
        assertNull(viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `onDeleteConfirmed does not crash when the repository throws, and still surfaces an error`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        repository.deleteThrows = true
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onDeleteRequested(item)
        runCurrent()

        viewModel.onDeleteConfirmed()
        runCurrent()

        assertEquals(
            "an unexpected throw from the repository must not silently drop the item from the list",
            1,
            viewModel.uiState.value.items.size,
        )
        assertEquals(item, viewModel.uiState.value.deleteError)
    }

    @Test
    fun `onDeleteErrorDismissed clears a shown delete error`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        repository.deleteSucceeds = false
        val viewModel = GalleryViewModel(repository, FakeRecordingPlayer(), ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onDeleteRequested(item)
        runCurrent()
        viewModel.onDeleteConfirmed()
        runCurrent()
        assertEquals(item, viewModel.uiState.value.deleteError)

        viewModel.onDeleteErrorDismissed()
        runCurrent()

        assertNull(viewModel.uiState.value.deleteError)
    }

    @Test
    fun `onDeleteConfirmed stops the player first when deleting the item currently loaded in it`() = runTest {
        val repository = FakeRecordingsRepository(listOf(row("blackbox_2026-01-01_00-00-00_5min.m4a")))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val item = viewModel.uiState.value.items.single().recording
        viewModel.onPlayPauseClicked(item) // now Playing
        runCurrent()

        viewModel.onDeleteRequested(item)
        viewModel.onDeleteConfirmed()
        runCurrent()

        assertEquals(1, player.stopCalled)
        assertEquals(PlaybackState.Idle, player.playback.value)
    }

    @Test
    fun `onDeleteConfirmed does not touch the player when deleting an item that is not loaded in it`() = runTest {
        val a = row("blackbox_2026-01-01_00-00-00_5min.m4a")
        val b = row("blackbox_2026-01-02_00-00-00_5min.m4a")
        val repository = FakeRecordingsRepository(listOf(a, b))
        val player = FakeRecordingPlayer()
        val viewModel = GalleryViewModel(repository, player, ioDispatcher = Dispatchers.Unconfined)
        subscribe(viewModel)
        val items = viewModel.uiState.value.items.map { it.recording }
        val itemA = items.single { it.uri == a.uri }
        val itemB = items.single { it.uri == b.uri }
        viewModel.onPlayPauseClicked(itemA) // A is playing
        runCurrent()

        viewModel.onDeleteRequested(itemB) // deleting B, which was never loaded
        viewModel.onDeleteConfirmed()
        runCurrent()

        assertEquals(0, player.stopCalled)
        assertEquals(PlaybackState.Playing(a.uri), player.playback.value)
    }
}
