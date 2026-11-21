package com.ashavaani.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.model.ConnectionState
import com.ashavaani.app.ui.theme.AshaColors

/**
 * High-contrast sunlit card using a crisp 1.5dp border instead of soft shadows that wash out outdoors.
 */
@Composable
fun SunlitCard(
    modifier: Modifier = Modifier,
    containerColor: Color = AshaColors.PureWhite,
    borderColor: Color = AshaColors.CardBorder,
    borderWidth: Dp = 1.5.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = if (onClick != null) {
            modifier.clip(shape).clickable(onClick = onClick)
        } else {
            modifier
        },
        shape = shape,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content,
        )
    }
}

/**
 * Compact status/badge pill used across Walk Mode, Registers, Visits, and PHC Referral sheets.
 */
@Composable
fun StatusPill(
    text: String,
    containerColor: Color = AshaColors.SurfaceMuted,
    contentColor: Color = AshaColors.InkSecondary,
    borderColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Top-bar compact TTFA / Connection telemetry pill (`● 28ms · MOCK`, `● 410ms · LIVE`, `● OFFLINE`).
 * Tapping opens the Settings & Speech Telemetry sheet.
 */
@Composable
fun TelemetryStatusPill(
    connectionState: ConnectionState,
    ttfaMs: Int?,
    gnaniMode: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (connectionState) {
        ConnectionState.CONNECTED -> {
            val msPart = ttfaMs?.let { "${it}ms · " } ?: ""
            AshaColors.VerifiedEmerald to "$msPart${gnaniMode.uppercase()}"
        }
        ConnectionState.RECONNECTING -> AshaColors.VoiceDiffAmber to "CONNECTING..."
        ConnectionState.OFFLINE_CACHE -> AshaColors.VoiceDiffAmber to "OFFLINE · CACHE"
    }

    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape)
            .background(AshaColors.SurfaceMuted)
            .border(1.dp, AshaColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                color = AshaColors.InkPrimary,
            ),
            maxLines = 1,
        )
    }
}
