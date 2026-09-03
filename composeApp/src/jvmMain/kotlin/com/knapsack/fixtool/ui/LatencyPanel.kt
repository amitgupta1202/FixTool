package com.knapsack.fixtool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.CorrelatedMessagePair
import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.LatencySeverity
import com.knapsack.fixtool.model.LatencyStatistics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Panel displaying latency tracking statistics and recent correlations
 */
@Composable
fun LatencyPanel(
    statistics: Map<CorrelationIdType, LatencyStatistics>,
    aggregateStatistics: LatencyStatistics,
    recentPairs: List<CorrelatedMessagePair>,
    warningThresholdMicros: Long,
    criticalThresholdMicros: Long,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppTheme.Colors.surface),
    ) {
        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surfaceHeader)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Latency Tracking",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.Colors.text,
                )

                SourceBadge()
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TooltipIconButton(
                    tooltip = "Clear Statistics",
                    onClick = onClear,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                TooltipIconButton(
                    tooltip = "Close Panel",
                    onClick = onClose,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = AppTheme.Separators.color, thickness = 1.dp)

        // Main content
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Aggregate statistics card
            if (aggregateStatistics.sampleCount > 0) {
                StatisticsCard(
                    title = "All Correlations",
                    stats = aggregateStatistics,
                    warningThresholdMicros = warningThresholdMicros,
                    criticalThresholdMicros = criticalThresholdMicros,
                )
            }

            // Per-type statistics
            val typesWithData = statistics.filter { it.value.sampleCount > 0 }
            if (typesWithData.isNotEmpty()) {
                Text(
                    text = "By Correlation Type",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.Colors.textSecondary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    typesWithData.forEach { (type, stats) ->
                        MiniStatisticsCard(
                            title = type.displayName,
                            stats = stats,
                            warningThresholdMicros = warningThresholdMicros,
                            criticalThresholdMicros = criticalThresholdMicros,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Recent correlations
            if (recentPairs.isNotEmpty()) {
                Text(
                    text = "Recent Correlations",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.Colors.textSecondary,
                )

                RecentCorrelationsTable(
                    pairs = recentPairs.take(50),
                    warningThresholdMicros = warningThresholdMicros,
                    criticalThresholdMicros = criticalThresholdMicros,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Empty state
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = AppTheme.Colors.textDisabled,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "No correlated messages yet",
                            fontSize = 12.sp,
                            color = AppTheme.Colors.textDisabled,
                        )
                        Text(
                            text = "Send request/response pairs with correlation IDs to measure latency",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.textDisabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where the numbers come from — the one thing about a latency that a reader has to know before
 * trusting it, so it is on the header rather than in the help. The tooltip says what the stamp
 * includes and what it leaves out; see `SocketStampFilter` for the mechanism.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceBadge() {
    val color = AppTheme.Colors.primary
    TooltipArea(
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .background(AppTheme.Colors.surfaceHeader, RoundedCornerShape(4.dp))
                        .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .widthIn(max = 360.dp),
            ) {
                Text(
                    text =
                        "Stamped at FixTool's socket, after TLS: a send when the kernel has taken the last byte, " +
                            "a reply when it has been framed out of the stream. The FIX engine's queue, parse and " +
                            "validation are not in the number. No privileges needed.",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.text,
                )
            }
        },
    ) {
        Box(
            modifier =
                Modifier
                    .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "Socket",
                fontSize = 10.sp,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Main statistics card
 */
@Composable
private fun StatisticsCard(
    title: String,
    stats: LatencyStatistics,
    warningThresholdMicros: Long,
    criticalThresholdMicros: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(6.dp))
                .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(6.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.Colors.text,
            )
            Text(
                text = "${stats.sampleCount} samples",
                fontSize = 11.sp,
                color = AppTheme.Colors.textSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatValue("Min", stats.formatMicros(stats.minMicros), AppTheme.Colors.text)
            StatValue("Max", stats.formatMicros(stats.maxMicros), getLatencyColor(stats.maxMicros, warningThresholdMicros, criticalThresholdMicros))
            StatValue(
                "Mean",
                stats.formatMicros(stats.meanMicros),
                getLatencyColor(stats.meanMicros.toLong(), warningThresholdMicros, criticalThresholdMicros),
            )
            StatValue("Median", stats.formatMicros(stats.medianMicros), AppTheme.Colors.text)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatValue("P90", stats.formatMicros(stats.p90Micros), getLatencyColor(stats.p90Micros, warningThresholdMicros, criticalThresholdMicros))
            StatValue("P95", stats.formatMicros(stats.p95Micros), getLatencyColor(stats.p95Micros, warningThresholdMicros, criticalThresholdMicros))
            StatValue("P99", stats.formatMicros(stats.p99Micros), getLatencyColor(stats.p99Micros, warningThresholdMicros, criticalThresholdMicros))
            StatValue("StdDev", stats.formatMicros(stats.stdDevMicros), AppTheme.Colors.text)
        }
    }
}

/**
 * Mini statistics card for per-type stats
 */
@Composable
private fun MiniStatisticsCard(
    title: String,
    stats: LatencyStatistics,
    warningThresholdMicros: Long,
    criticalThresholdMicros: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
                .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.Colors.text,
            )
            Text(
                text = "${stats.sampleCount}",
                fontSize = 10.sp,
                color = AppTheme.Colors.textSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Mean", fontSize = 9.sp, color = AppTheme.Colors.textSecondary)
                Text(
                    text = stats.formatMicros(stats.meanMicros),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = getLatencyColor(stats.meanMicros.toLong(), warningThresholdMicros, criticalThresholdMicros),
                )
            }
            Column {
                Text("P99", fontSize = 9.sp, color = AppTheme.Colors.textSecondary)
                Text(
                    text = stats.formatMicros(stats.p99Micros),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = getLatencyColor(stats.p99Micros, warningThresholdMicros, criticalThresholdMicros),
                )
            }
        }
    }
}

/**
 * Individual stat value display
 */
@Composable
private fun StatValue(
    label: String,
    value: String,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = AppTheme.Colors.textSecondary,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

/**
 * Table showing recent correlated message pairs
 */
@Composable
private fun RecentCorrelationsTable(
    pairs: List<CorrelatedMessagePair>,
    warningThresholdMicros: Long,
    criticalThresholdMicros: Long,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(4.dp))
                .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(4.dp)),
    ) {
        // Header row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surfaceHeader)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text("Type", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.Colors.textSecondary, modifier = Modifier.weight(0.8f))
            Text(
                "Correlation ID",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.weight(1.2f),
            )
            Text("Send Time", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.Colors.textSecondary, modifier = Modifier.weight(1f))
            Text("Recv Time", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.Colors.textSecondary, modifier = Modifier.weight(1f))
            Text("RTT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.Colors.textSecondary, modifier = Modifier.weight(0.8f))
        }

        HorizontalDivider(color = AppTheme.Colors.border, thickness = 1.dp)

        // Data rows
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(pairs) { pair ->
                val severity = pair.getSeverity(warningThresholdMicros, criticalThresholdMicros)
                val rttColor = getLatencyColorFromSeverity(severity)

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Correlation type
                    Text(
                        text = pair.sendTimestamp.correlationType.displayName,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppTheme.Colors.text,
                        modifier = Modifier.weight(0.8f),
                    )

                    // Correlation ID
                    Text(
                        text = pair.sendTimestamp.correlationId.take(15),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppTheme.Colors.text,
                        modifier = Modifier.weight(1.2f),
                    )

                    // Send time
                    Text(
                        text = formatMicrosToTime(pair.sendTimestamp.timestampMicros, timeFormatter),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppTheme.Colors.messageOutgoing,
                        modifier = Modifier.weight(1f),
                    )

                    // Receive time
                    Text(
                        text = formatMicrosToTime(pair.receiveTimestamp.timestampMicros, timeFormatter),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppTheme.Colors.messageIncoming,
                        modifier = Modifier.weight(1f),
                    )

                    // RTT with color
                    Text(
                        text = pair.formatRoundTripTime(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = rttColor,
                        modifier = Modifier.weight(0.8f),
                    )
                }

                HorizontalDivider(color = AppTheme.Colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        }
    }
}

/**
 * Helper to format microseconds timestamp to time string
 */
private fun formatMicrosToTime(
    micros: Long,
    formatter: DateTimeFormatter,
): String {
    val instant = Instant.ofEpochMilli(micros / 1000)
    val localTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
    return localTime.format(formatter)
}

/**
 * Get color based on latency value and thresholds
 */
private fun getLatencyColor(
    micros: Long,
    warningThreshold: Long,
    criticalThreshold: Long,
): Color =
    when {
        micros >= criticalThreshold -> AppTheme.Colors.error
        micros >= warningThreshold -> AppTheme.Colors.warning
        else -> AppTheme.Colors.primary
    }

/**
 * Get color from severity enum
 */
private fun getLatencyColorFromSeverity(severity: LatencySeverity): Color =
    when (severity) {
        LatencySeverity.CRITICAL -> AppTheme.Colors.error
        LatencySeverity.WARNING -> AppTheme.Colors.warning
        LatencySeverity.NORMAL -> AppTheme.Colors.primary
    }
