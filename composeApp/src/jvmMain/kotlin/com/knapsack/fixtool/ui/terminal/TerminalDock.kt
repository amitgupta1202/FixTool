package com.knapsack.fixtool.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.ui.AppTheme
import java.awt.Cursor
import java.io.File

/**
 * The embedded terminal docked as a resizable pane at the bottom of the main window — the IntelliJ
 * layout. The grip at the top resizes it (drag up to grow); the header's × closes it, same as the
 * toolbar toggle.
 *
 * It injects [controlUrl] into the shell's environment so a `claude` run inside drives this FixTool
 * over MCP with no port to configure. Working dir defaults to the user's home (FIXTOOL_TERMINAL_CWD
 * overrides).
 */
@Composable
fun TerminalDock(
    controlUrl: String,
    controlToken: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val workingDir =
        remember {
            System.getenv("FIXTOOL_TERMINAL_CWD")?.ifBlank { null }?.let(::File)
                ?: File(System.getProperty("user.home"))
        }
    var heightDp by remember { mutableStateOf(320.dp) }

    Column(modifier.fillMaxWidth()) {
        // Resize grip — drag up to grow the terminal, down to shrink it.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0xFF3A3A3A))
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            heightDp = (heightDp - with(density) { dragAmount.toDp() }).coerceIn(120.dp, 720.dp)
                        }
                    },
        )
        // Header — title on the left, close on the right.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Terminal",
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close terminal",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // The terminal itself, at the resizable height.
        TerminalPanel(
            workingDir = workingDir,
            controlUrl = controlUrl,
            controlToken = controlToken,
            modifier = Modifier.fillMaxWidth().height(heightDp),
        )
    }
}
