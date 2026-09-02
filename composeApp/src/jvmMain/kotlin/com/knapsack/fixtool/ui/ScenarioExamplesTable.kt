package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.ExampleRow

/**
 * **The outline's table, in the editor.**
 *
 * A scenario is already parameterized; this is the table that says what to parameterize it *with*. Every
 * column is a variable name seeded into the run's scope before its first step, so a cell is not a setting
 * — it is the value `${symbol}` will have for that run.
 *
 * It sits across the foot of the editor rather than inside either pane: a table is wide, the step list is
 * a column of sentences, and putting one inside the other would cost the sentences their room. Collapsed
 * it is a single line, so a scenario with no table pays nothing for the feature existing.
 */
@Composable
fun ScenarioExamplesTable(
    columns: List<String>,
    rows: List<ExampleRow>,
    /** ↑ out / ↓ in / ↑↓ — which side of the wire reads this column, derived from the steps themselves. */
    columnRole: (String) -> String,
    /** Columns no step reads: a half-finished table, said out loud rather than discovered by a green run. */
    unread: List<String>,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onColumns: (List<String>) -> Unit,
    onRows: (List<ExampleRow>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("examples-table")) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            SlimButton(
                if (expanded) "▾ Examples" else "▸ Examples",
                onClick = { onExpand(!expanded) },
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.testTag("examples-toggle"),
            )
            Text(summary(columns, rows), color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.weight(1f))
            if (expanded) {
                // A new column gets a cell in every row, and a new row a cell for every column — empty, as
                // "Extract to example column" already does. A row without the key seeded nothing, so
                // `${qty}` shipped as ten literal characters while the row beside it, whose cell had been
                // typed into and cleared, sent `38=`. The two look identical on screen.
                SlimButton(
                    "+ column",
                    onClick = {
                        val name = nextColumnName(columns)
                        onColumns(columns + name)
                        onRows(rows.map { it.copy(values = it.values + (name to "")) })
                    },
                    modifier = Modifier.testTag("examples-add-column"),
                )
                SlimButton(
                    "+ row",
                    onClick = { onRows(rows + ExampleRow(name = nextRowName(rows), values = columns.associateWith { "" })) },
                    enabled = columns.isNotEmpty(),
                    modifier = Modifier.testTag("examples-add-row"),
                )
            }
        }
        if (!expanded) return@Column
        if (columns.isEmpty()) {
            Text(
                "A column is a variable name — add one, then write the value each run should have. Steps read " +
                    "it as \${name}, exactly as they read a name a Send mints.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            return@Column
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
        ) {
            HeaderRow(columns, columnRole, unread, onColumns, onRows, rows)
            rows.forEachIndexed { index, row ->
                RowLine(
                    index = index,
                    row = row,
                    columns = columns,
                    onChange = { edited -> onRows(rows.mapIndexed { i, r -> if (i == index) edited else r }) },
                    onRemove = { onRows(rows.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
        if (unread.isNotEmpty()) {
            Text(
                "No step reads " + unread.joinToString(", ") { "\${$it}" } +
                    " — the column is seeded and nothing uses it, so these rows prove nothing about it.",
                color = AppTheme.Colors.warning,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("examples-unread"),
            )
        }
    }
}

@Composable
private fun HeaderRow(
    columns: List<String>,
    columnRole: (String) -> String,
    unread: List<String>,
    onColumns: (List<String>) -> Unit,
    onRows: (List<ExampleRow>) -> Unit,
    rows: List<ExampleRow>,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text("row", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.width(ROW_NAME_WIDTH.dp))
        columns.forEachIndexed { index, column ->
            Column(modifier = Modifier.width(CELL_WIDTH.dp).padding(end = 6.dp)) {
                // **What is typed stays local until it is a name the table can take** — not blank, and not a
                // sibling's. Applied on every keystroke, a rename that passed through a sibling's name merged
                // the two columns' cells for good: `symbol` backspaced toward `symX` is `sym` on the way, and
                // with a `sym` column beside it one row value was discarded at that keystroke and never came
                // back. A blank name went in the same door, and nothing could ever reference it.
                var typed by remember(column) { mutableStateOf(column) }
                val pending = typed != column
                val taken = columns.withIndex().any { (i, c) -> i != index && c == typed }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SlimField(
                        value = typed,
                        onValueChange = { renamed ->
                            typed = renamed
                            val clashes = columns.withIndex().any { (i, c) -> i != index && c == renamed }
                            if (renamed.isNotBlank() && !clashes) {
                                // Renaming a column renames the cells with it: a row whose keys drifted from
                                // the columns would seed nothing, and the value would vanish without a word.
                                val from = columns[index]
                                onColumns(columns.mapIndexed { i, c -> if (i == index) renamed else c })
                                onRows(rows.map { r -> r.copy(values = r.values.mapKeys { (k, _) -> if (k == from) renamed else k }) })
                            }
                        },
                        monospace = true,
                        modifier = Modifier.width((CELL_WIDTH - 46).dp).testTag("examples-column-$index"),
                    )
                    Text(
                        when {
                            pending && typed.isBlank() -> "name needed"
                            pending && taken -> "name taken"
                            else -> columnRole(column)
                        },
                        color = if (pending || column in unread) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 3.dp).testTag("examples-column-role-$index"),
                    )
                    SlimButton(
                        "×",
                        onClick = {
                            val dropped = columns[index]
                            onColumns(columns.filterIndexed { i, _ -> i != index })
                            onRows(rows.map { r -> r.copy(values = r.values - dropped) })
                        },
                        color = AppTheme.Colors.textDisabled,
                        modifier = Modifier.testTag("examples-drop-column-$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowLine(
    index: Int,
    row: ExampleRow,
    columns: List<String>,
    onChange: (ExampleRow) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp).testTag("examples-row-$index"),
    ) {
        SlimField(
            value = row.name,
            onValueChange = { onChange(row.copy(name = it)) },
            placeholder = "name this row",
            modifier = Modifier.width(ROW_NAME_WIDTH.dp).testTag("examples-row-name-$index"),
        )
        columns.forEachIndexed { column, name ->
            SlimField(
                value = row.values[name].orEmpty(),
                onValueChange = { onChange(row.copy(values = row.values + (name to it))) },
                monospace = true,
                modifier = Modifier.width((CELL_WIDTH - 8).dp).padding(end = 6.dp).testTag("examples-cell-$index-$column"),
            )
        }
        // Parked, not deleted — the same bargain a muted step keeps, for the same reason: an author
        // bisecting a table wants the row's shape back afterwards.
        SlimButton(
            if (row.muted) "muted" else "mute",
            onClick = { onChange(row.copy(muted = !row.muted)) },
            color = if (row.muted) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
            modifier = Modifier.testTag("examples-mute-$index"),
        )
        SlimButton("×", onClick = onRemove, color = AppTheme.Colors.textDisabled, modifier = Modifier.testTag("examples-drop-row-$index"))
    }
}

private fun summary(columns: List<String>, rows: List<ExampleRow>): String {
    if (columns.isEmpty() && rows.isEmpty()) return "no table — this scenario runs once"
    val live = rows.count { !it.muted }
    val parked = rows.size - live
    return "$live row${if (live == 1) "" else "s"} × ${columns.size} column${if (columns.size == 1) "" else "s"}" +
        if (parked > 0) " · $parked parked" else ""
}

/** `col1`, `col2`, … — a name, not a blank, so the header is editable the moment it appears. */
private fun nextColumnName(columns: List<String>): String {
    var n = columns.size + 1
    while ("col$n" in columns) n++
    return "col$n"
}

private fun nextRowName(rows: List<ExampleRow>): String {
    var n = rows.size + 1
    while (rows.any { it.name == "row $n" }) n++
    return "row $n"
}

private const val ROW_NAME_WIDTH = 150
private const val CELL_WIDTH = 150
