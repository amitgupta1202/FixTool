package com.knapsack.fixtool.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import java.awt.Desktop
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent

/**
 * Help dialog that displays HTML documentation.
 * Uses Swing JEditorPane to render the HTML content.
 */
@Composable
fun HelpDialog(
    onClose: () -> Unit,
) {
    val dialogState = rememberDialogState(width = 1000.dp, height = 700.dp)

    Dialog(
        onCloseRequest = onClose,
        title = "FixTool Help",
        state = dialogState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.Colors.surface),
        ) {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(AppTheme.Colors.background)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "FixTool Help & Documentation",
                    color = AppTheme.Colors.text,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppTheme.Colors.text,
                    )
                }
            }

            // HTML content viewer
            Box(modifier = Modifier.fillMaxSize()) {
                HtmlViewer()
            }
        }
    }
}

/**
 * Component that renders HTML content using Swing JEditorPane.
 */
@Composable
private fun HtmlViewer() {
    val htmlContent = remember { loadHelpHtml() }

    SwingPanel(
        background = AppTheme.Colors.surface,
        modifier = Modifier.fillMaxSize(),
        factory = {
            // Create JEditorPane for HTML rendering
            val editorPane = JEditorPane("text/html", htmlContent).apply {
                isEditable = false

                // Handle hyperlink clicks
                addHyperlinkListener { event ->
                    if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            Desktop.getDesktop().browse(event.url.toURI())
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            // Wrap in scroll pane
            JScrollPane(editorPane).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        },
    )
}

/**
 * Loads the HTML help content from resources.
 */
private fun loadHelpHtml(): String {
    return try {
        val inputStream = object {}.javaClass.getResourceAsStream("/help.html")
        inputStream?.bufferedReader()?.readText() ?: """
            <!DOCTYPE html>
            <html>
            <head><title>Help Not Found</title></head>
            <body>
                <h1>Help documentation not found</h1>
                <p>The help file could not be loaded.</p>
            </body>
            </html>
        """.trimIndent()
    } catch (e: Exception) {
        """
            <!DOCTYPE html>
            <html>
            <head><title>Error</title></head>
            <body>
                <h1>Error loading help</h1>
                <p>Error: ${e.message}</p>
            </body>
            </html>
        """.trimIndent()
    }
}
