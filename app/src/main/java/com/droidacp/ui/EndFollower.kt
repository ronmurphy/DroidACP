package com.droidacp.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the newest message in view while the agent writes, unless the user scrolled up to read.
 *
 * Only the user's own scrolling turns following off (scrolling up) or on (getting back
 * to the end). Anything else that moves the end out of view, such as the keyboard opening
 * as a permission dialog closes, leaves it on, and the list goes back to the end.
 */
class EndFollower(private val list: LazyListState) {
    /** Whether the list should stay at the end. */
    var following by mutableStateOf(true)
        private set

    // How many of our own scrolls are running, so they aren't mistaken for the user's.
    private var ourScrolls = 0

    /** Go to the end and keep following it. */
    suspend fun scrollToEnd() {
        following = true
        ourScrolls++
        try {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.scrollToItem(count - 1)
            list.scrollBy(100_000f)
        } catch (e: CancellationException) {
            // The user started scrolling, or another of our scrolls took over: let it.
            currentCoroutineContext().ensureActive()
        } finally {
            ourScrolls--
        }
    }

    /** Go to the end if we're following (call when the conversation grows). */
    suspend fun keepUp() {
        if (following && !(list.isScrollInProgress && ourScrolls == 0)) scrollToEnd()
    }

    /** Runs while the list is on screen. */
    suspend fun watch() = coroutineScope {
        launch {
            snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, more) ->
                when {
                    !more -> following = true
                    scrolling && ourScrolls == 0 -> following = false
                }
            }
        }
        // The list gets shorter when the keyboard opens (and taller when it closes).
        launch {
            snapshotFlow { list.layoutInfo.viewportSize.height }
                .distinctUntilChanged()
                .collect { keepUp() }
        }
    }
}
