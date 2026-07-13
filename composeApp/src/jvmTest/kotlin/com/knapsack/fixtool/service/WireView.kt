package com.knapsack.fixtool.service

/**
 * A [MessageView] straight from an ordered list of `tag=value` — which, under the sequence model, is
 * all a message is. Shared by every test that needs one, so no test can quietly invent a view whose
 * shape the runner does not actually produce.
 */
fun wireView(vararg fields: Pair<Int, String>): MessageView = wireView(fields.toList())

fun wireView(fields: List<Pair<Int, String>>): MessageView =
    object : MessageView {
        override fun fields(): List<Pair<Int, String>> = fields
    }
