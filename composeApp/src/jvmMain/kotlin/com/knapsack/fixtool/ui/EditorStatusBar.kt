package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.MessageEditorState

/**
 * Status bar for the message editor showing current message state
 * Displays message name, modification status, and user tags in a subtle footer-style bar
 */
@Composable
fun EditorStatusBar(
    editorState: MessageEditorState,
    modifier: Modifier = Modifier,
) {
    // Use consistent theme colors - subtle footer-like appearance
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Simple message state text only
        val displayText =
            when (editorState) {
                is MessageEditorState.New -> "New message"
                is MessageEditorState.Clean -> editorState.messageName
                is MessageEditorState.Dirty -> "${editorState.messageName} (modified)"
            }

        Text(
            text = displayText,
            fontSize = 10.sp,
            color = AppTheme.Colors.textSecondary,
        )
    }
}

/**
 * Preview helper for showing different states
 */
@Composable
private fun EditorStatusBarPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorStatusBar(editorState = MessageEditorState.New)

        EditorStatusBar(
            editorState =
                MessageEditorState.Clean(
                    messageId = "123",
                    messageName = "NewOrderSingle-Template",
                    userTags = setOf("trader1", "trader2"),
                ),
        )

        EditorStatusBar(
            editorState =
                MessageEditorState.Dirty(
                    messageId = "123",
                    messageName = "NewOrderSingle-Template",
                    userTags = setOf("trader1", "trader2", "trader3", "trader4"),
                ),
        )
    }
}
