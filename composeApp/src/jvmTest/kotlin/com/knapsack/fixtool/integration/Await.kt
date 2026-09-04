package com.knapsack.fixtool.integration

import androidx.compose.runtime.snapshots.Snapshot

/**
 * A polled predicate, evaluated against a stable view of the state it reads.
 *
 * These waits read `viewModel.sessions` — a Compose `SnapshotStateList` the view model mutates on its
 * own dispatcher, never on the test's thread. A venue mints a pane the moment a client logs on
 * (`FixMessageViewModel.attachVenueClient`, hopped onto `viewModelScope` precisely so Compose reads a
 * list only one thread writes), which is *exactly* the arrival most of these waits are waiting for. So
 * the window where the list grows and the window where the test iterates it are the same window, and
 * iterating a list that moves underneath you fails — as `ConcurrentModificationException` when the
 * modification count changes mid-iteration, and as `IndexOutOfBoundsException` when it shrinks. Both
 * are the same event: the read tore.
 *
 * That is not a defect in the app. [com.knapsack.fixtool.control.ControlServer] reads the same list as
 * `onEdt { viewModel.sessions.toList() }`, taking its copy on the thread that does the writing. It is a
 * defect in a *poll* that cannot survive the event it is polling for.
 *
 * The fix is to read what Compose is built to give a reader on another thread: a snapshot. Inside one,
 * every state object reads as of the instant the snapshot was taken, so the list cannot move mid-
 * iteration and there is nothing to tear. That is better than catching the two exceptions, because
 * catching them means a predicate that fails for a *real* reason — an index bug of its own — comes back
 * as "not yet" and is reported a timeout later, with the cause thrown away. Nothing is swallowed here.
 *
 * Found by CI on the v1.16.0 tag, in `ExampleWorkspaceIntegrationTest.the bundled scenario runs green
 * twice`: two clients logging on to one venue is the widest that window gets, and a loaded shared
 * runner is where it opens. It had passed locally every time.
 */
internal fun settled(predicate: () -> Boolean): Boolean {
    val snapshot = Snapshot.takeSnapshot()
    return try {
        snapshot.enter(predicate)
    } finally {
        snapshot.dispose()
    }
}
