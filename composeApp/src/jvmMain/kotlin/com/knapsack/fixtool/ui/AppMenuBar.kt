package com.knapsack.fixtool.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import org.slf4j.LoggerFactory
import java.awt.Desktop

private val logger = LoggerFactory.getLogger("com.knapsack.fixtool.ui.AppMenuBar")

/**
 * **The menu bar: the catalogue, drawn.**
 *
 * FixTool had no menu bar, so everything it could do had to be on screen to be found — which is how the
 * toolbar came to hold Add blank line beside Connect. The toolbar is the favourites now and this is the
 * catalogue: every action has a name and a shortcut somewhere a reader can find it without being told.
 *
 * Nothing is decided here. The rows, their state and their shortcuts are [AppMenuState]'s, built by
 * [PublishAppMenus] from what the toolbar, the stripes and the pane header already read, and this only turns
 * them into Swing menu items. On a Mac they go to the system menu bar (see `main`), and the FixTool menu is
 * the application menu the system already draws, so its rows are handed to the system rather than drawn a
 * second time beside it.
 */
@Composable
fun FrameWindowScope.AppMenuBar(state: AppMenuState) {
    if (IS_MAC) MacApplicationMenu(state)
    MenuBar {
        state.menus
            .filterNot { IS_MAC && it.application }
            .forEach { menu ->
                key(menu.title) {
                    Menu(menu.title) { Rows(menu.rows) }
                }
            }
    }
}

@Composable
private fun MenuScope.Rows(rows: List<MenuRow>) {
    rows.forEach { row ->
        key(row.tag) {
            when (row) {
                is MenuItem -> ItemRow(row)
                is Submenu -> Menu(row.label, enabled = row.enabled) { Rows(row.rows) }
                is MenuSeparator -> Separator()
            }
        }
    }
}

@Composable
private fun MenuScope.ItemRow(item: MenuItem) {
    val shortcut = item.chord?.keyShortcut
    when {
        item.checked == null ->
            Item(item.label, enabled = item.enabled, shortcut = shortcut, onClick = item.onClick)
        item.choice ->
            RadioButtonItem(
                item.label,
                selected = item.checked,
                enabled = item.enabled,
                shortcut = shortcut,
                onClick = item.onClick,
            )
        else ->
            CheckboxItem(
                item.label,
                checked = item.checked,
                enabled = item.enabled,
                shortcut = shortcut,
                onCheckedChange = { item.onClick() },
            )
    }
}

/**
 * **The Mac's application menu, answering with the FixTool menu's rows.**
 *
 * The system draws About, Settings… and Quit itself, under the application's name, and answers ⌘, and ⌘Q.
 * What it needs is to be told what those rows do: without a handler Settings… is greyed, and Quit ends the
 * process without the window's own close, which is the one that logs every session out before it goes.
 */
@Composable
private fun MacApplicationMenu(state: AppMenuState) {
    val menus = rememberUpdatedState(state.menus)
    DisposableEffect(Unit) {
        fun row(tag: String): MenuItem? =
            menus.value
                .firstOrNull { it.application }
                ?.rows
                ?.items()
                ?.firstOrNull { it.tag == tag }

        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
        runCatching {
            if (desktop?.isSupported(Desktop.Action.APP_PREFERENCES) == true) {
                desktop.setPreferencesHandler { row("menu-settings")?.onClick?.invoke() }
            }
            if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) {
                desktop.setQuitHandler { _, response ->
                    // Cancelled as far as the system is concerned, because the window's own close is what
                    // quits: it logs the sessions out first, and it ends the process when it is done.
                    response.cancelQuit()
                    row("menu-quit")?.onClick?.invoke()
                }
            }
        }.onFailure { logger.warn("Could not attach the application menu's handlers", it) }
        onDispose {
            runCatching {
                if (desktop?.isSupported(Desktop.Action.APP_PREFERENCES) == true) desktop.setPreferencesHandler(null)
                if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler(null)
            }
        }
    }
}
