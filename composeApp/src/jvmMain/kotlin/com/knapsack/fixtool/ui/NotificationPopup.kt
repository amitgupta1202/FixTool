package com.knapsack.fixtool.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Notification
import com.knapsack.fixtool.model.NotificationType
import kotlinx.coroutines.delay

/**
 * Notification popup container that displays notifications in the bottom-right corner
 * Similar to IntelliJ IDEA's notification system
 */
@Composable
fun NotificationPopupContainer(
    notifications: List<Notification>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(16.dp)
                    .widthIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            notifications.takeLast(5).forEach { notification ->
                NotificationPopup(
                    notification = notification,
                    onDismiss = { onDismiss(notification.id) },
                )
            }
        }
    }
}

/**
 * Individual notification popup balloon
 */
@Composable
private fun NotificationPopup(
    notification: Notification,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-dismiss after 10 seconds
    LaunchedEffect(notification.id) {
        delay(10000)
        onDismiss()
    }

    val (backgroundColor, borderColor, icon, iconTint) =
        when (notification.type) {
            NotificationType.ERROR ->
                NotificationStyle(
                    backgroundColor = Color(0xFF3D2828),
                    borderColor = Color(0xFFFF5555),
                    icon = Icons.Default.Error,
                    iconTint = Color(0xFFFF5555),
                )

            NotificationType.WARNING ->
                NotificationStyle(
                    backgroundColor = Color(0xFF3D3528),
                    borderColor = Color(0xFFFFA500),
                    icon = Icons.Default.Warning,
                    iconTint = Color(0xFFFFA500),
                )

            NotificationType.INFO ->
                NotificationStyle(
                    backgroundColor = Color(0xFF283D3D),
                    borderColor = Color(0xFF5599FF),
                    icon = Icons.Default.Info,
                    iconTint = Color(0xFF5599FF),
                )

            NotificationType.SUCCESS ->
                NotificationStyle(
                    backgroundColor = Color(0xFF283D28),
                    borderColor = Color(0xFF55FF55),
                    icon = Icons.Default.CheckCircle,
                    iconTint = Color(0xFF55FF55),
                )
        }

    AnimatedVisibility(
        visible = true,
        enter =
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(300)),
        exit =
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(300)),
    ) {
        Row(
            modifier =
                modifier
                    .background(backgroundColor, RoundedCornerShape(4.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .widthIn(min = 250.dp, max = 400.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )

            // Message text
            Text(
                text = notification.message,
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f),
            )

            // Close button
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = Color(0xFFB0B0B0),
                modifier =
                    Modifier
                        .size(16.dp)
                        .clickable { onDismiss() },
            )
        }
    }
}

/**
 * Data class to hold notification styling properties
 */
private data class NotificationStyle(
    val backgroundColor: Color,
    val borderColor: Color,
    val icon: ImageVector,
    val iconTint: Color,
)
