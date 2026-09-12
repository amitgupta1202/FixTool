package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **Staging a record on disk the way the app does: write it, then say so.**
 *
 * The documents that draw a run no longer read the record stores in composition — they read
 * [FixMessageViewModel.runConfigurations], which every door in the app that writes a record refreshes on its
 * way past. A test that reaches around the ViewModel to its store has to do the same, or it has written a
 * file nothing has been told about and the document it renders is looking at an empty list.
 *
 * One helper per kind rather than one per test file, because four test classes had the same two lines in
 * thirty places and a fifth would have had them too.
 */
internal fun FixMessageViewModel.stageLoadRecord(record: LoadRecord) {
    loadRecordStore.write(record)
    refreshRunConfigurations()
}

internal fun FixMessageViewModel.stageLoadRecord(report: LoadReport) {
    loadRecordStore.write(report)
    refreshRunConfigurations()
}

internal fun FixMessageViewModel.stageRunSet(set: RunSet) {
    runRecordStore.writeSet(set)
    refreshRunConfigurations()
}
