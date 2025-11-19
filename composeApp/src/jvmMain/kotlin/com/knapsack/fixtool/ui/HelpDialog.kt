package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import java.awt.Color
import java.awt.Desktop
import java.awt.Rectangle
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

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
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AppTheme.Colors.surface),
        ) {
            // Title bar
            Row(
                modifier =
                    Modifier
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
            val editorPane =
                JEditorPane("text/html", htmlContent).apply {
                    isEditable = false

                    // Make the caret invisible but keep it to avoid NPE
                    caretColor = Color(0, 0, 0, 0) // Transparent
                    (caret as? DefaultCaret)?.apply {
                        isVisible = false
                        blinkRate = 0
                    }
                    highlighter = null // Remove text selection highlighting

                    val self = this // Capture reference for use in lambda

                    // Handle hyperlink clicks
                    addHyperlinkListener { event ->
                        if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            val description = event.description
                            try {
                                // Handle internal anchor links (like #getting-started)
                                if (description != null && description.startsWith("#")) {
                                    // Extract anchor reference (remove the # prefix)
                                    val anchor = description.substring(1)
                                    // Manually find and scroll to the anchor
                                    SwingUtilities.invokeLater {
                                        scrollToAnchor(self, anchor)
                                    }
                                } else {
                                    // Handle external links (http/https)
                                    if (Desktop.isDesktopSupported()) {
                                        val desktop = Desktop.getDesktop()
                                        if (desktop.isSupported(Desktop.Action.BROWSE)) {
                                            desktop.browse(event.url.toURI())
                                        } else {
                                            println("Desktop browse action not supported. URL: ${event.url}")
                                        }
                                    } else {
                                        println("Desktop API not supported. URL: ${event.url}")
                                    }
                                }
                            } catch (e: Exception) {
                                println("Failed to handle hyperlink: $description")
                                e.printStackTrace()
                            }
                        }
                    }
                }

            // Wrap in scroll pane
            JScrollPane(editorPane).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED

                // Ensure we start at the top of the document after rendering
                SwingUtilities.invokeLater {
                    verticalScrollBar.value = 0
                    horizontalScrollBar.value = 0
                }
            }
        },
    )
}

/**
 * Loads the HTML help content from resources.
 */
private fun loadHelpHtml(): String =
    try {
        val inputStream = object {}.javaClass.getResourceAsStream("/help.html")
        inputStream?.bufferedReader()?.readText()
            ?: """
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

/**
 * Scrolls the JEditorPane to the element with the given id attribute.
 * This is needed because scrollToReference() doesn't work when HTML is loaded as a string.
 */
private fun scrollToAnchor(editorPane: JEditorPane, anchorId: String) {
    try {
        val doc = editorPane.document as? HTMLDocument ?: return
        val root = doc.defaultRootElement

        // Recursively search for the element with the matching id
        fun findElementWithId(elem: javax.swing.text.Element, targetId: String): javax.swing.text.Element? {
            val attrs = elem.attributes
            val nameAttr = attrs.getAttribute(HTML.Attribute.ID)
            if (nameAttr?.toString() == targetId) {
                return elem
            }

            // Search children
            for (i in 0 until elem.elementCount) {
                val found = findElementWithId(elem.getElement(i), targetId)
                if (found != null) return found
            }
            return null
        }

        val targetElement = findElementWithId(root, anchorId)
        if (targetElement != null) {
            // Get the position and scroll to it
            val pos = targetElement.startOffset
            val rect = editorPane.modelToView(pos)
            if (rect != null) {
                // Get the viewport height to calculate better scroll position
                val visibleRect = editorPane.visibleRect
                val viewportHeight = visibleRect.height

                // Position the element at the top 20% of the viewport for better visibility
                // This ensures the header and some content below it are visible
                val targetY = maxOf(0, rect.y - (viewportHeight * 0.2).toInt())

                val scrollRect = Rectangle(rect.x, targetY, rect.width, viewportHeight)
                editorPane.scrollRectToVisible(scrollRect)
            }
        } else {
            println("Anchor not found: $anchorId")
        }
    } catch (e: Exception) {
        println("Error scrolling to anchor: $anchorId")
        e.printStackTrace()
    }
}
