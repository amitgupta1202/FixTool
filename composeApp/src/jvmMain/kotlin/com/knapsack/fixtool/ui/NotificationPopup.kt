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

    val (backgroundColor, borderColor, icon, iconTint) = getNotificationStyle(notification.type)

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
                    .background(backgroundColor, notificationShape)
                    .border(1.dp, borderColor, notificationShape)
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
                modifier = notificationIconSize,
            )

            // Message text
            Text(
                text = notification.message,
                color = notificationTextColor,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f),
            )

            // Close button
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = closeButtonColor,
                modifier =
                    closeButtonSize
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

// Color constants
private val errorBackgroundColor = Color(0xFF3D2828)
private val errorBorderColor = Color(0xFFFF5555)
private val warningBackgroundColor = Color(0xFF3D3528)
private val warningBorderColor = Color(0xFFFFA500)
private val infoBackgroundColor = Color(0xFF283D3D)
private val infoBorderColor = Color(0xFF5599FF)
private val successBackgroundColor = Color(0xFF283D28)
private val successBorderColor = Color(0xFF55FF55)
private val notificationTextColor = Color(0xFFE0E0E0)
private val closeButtonColor = Color(0xFFB0B0B0)

// Modifier constants
private val notificationIconSize = Modifier.size(20.dp)
private val closeButtonSize = Modifier.size(16.dp)
private val notificationShape = RoundedCornerShape(4.dp)

// Helper function
private fun getNotificationStyle(type: NotificationType): NotificationStyle =
    when (type) {
        NotificationType.ERROR ->
            NotificationStyle(
                backgroundColor = errorBackgroundColor,
                borderColor = errorBorderColor,
                icon = Icons.Default.Error,
                iconTint = errorBorderColor,
            )
        NotificationType.WARNING ->
            NotificationStyle(
                backgroundColor = warningBackgroundColor,
                borderColor = warningBorderColor,
                icon = Icons.Default.Warning,
                iconTint = warningBorderColor,
            )
        NotificationType.INFO ->
            NotificationStyle(
                backgroundColor = infoBackgroundColor,
                borderColor = infoBorderColor,
                icon = Icons.Default.Info,
                iconTint = infoBorderColor,
            )
        NotificationType.SUCCESS ->
            NotificationStyle(
                backgroundColor = successBackgroundColor,
                borderColor = successBorderColor,
                icon = Icons.Default.CheckCircle,
                iconTint = successBorderColor,
            )
    }
