package com.knapsack.fixtool.service

import quickfix.FieldMap

/**
 * The number of group entries under [tag], without QuickFIX's side effect.
 *
 * `FieldMap.getGroupCount` reaches its answer through `getGroups`, whose `computeIfAbsent` files an
 * empty list under any tag it is merely *asked* about — a write, on the shared parsed message, from
 * whatever thread happened to render it. The views ask about every tag they draw, on every
 * recomposition, against the same `quickfix.Message` the assertion engine and the template
 * extractor read. `hasGroup` is a plain `containsKey`, so ask that first and touch nothing.
 */
fun FieldMap.groupCountSafe(tag: Int): Int = if (hasGroup(tag)) getGroupCount(tag) else 0
