package com.ashavaani.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import kotlinx.coroutines.delay

/**
 * Village Follow-Up Queue Tab (Tab 3).
 * Escalated PHC referrals pin to top in Urgent Crimson with 1-tap `Mark Done` and `Start Visit`.
 */
@Composable
fun FollowUpScreen(
    followUps: List<FollowUp>,
    activeFilter: FollowUpFilter,
    statusToast: String?,
    errorBanner: String?,
    onSelectFilter: (FollowUpFilter) -> Unit,
    onRefresh: () -> Unit,
    onMarkComplete: (FollowUp) -> Unit,
    onStartVisit: (beneficiaryId: String) -> Unit,
    onClearToast: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(statusToast) {
        if (statusToast != null) {
            delay(3_200L)
            onClearToast()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        // Filter Chip Row + Refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FollowUpFilter.entries.forEach { filter ->
                    val selected = filter == activeFilter
                    val activeBg = if (filter == FollowUpFilter.ESCALATED) {
                        AshaColors.DangerCrimson
                    } else {
                        AshaColors.ClinicalTeal
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) activeBg else AshaColors.PureWhite,
                        border = BorderStroke(
                            1.5.dp,
                            if (selected) activeBg else AshaColors.CardBorder,
                        ),
                        modifier = Modifier.clickable { onSelectFilter(filter) },
                    ) {
                        Text(
                            text = filter.label,
                            color = if (selected) AshaColors.PureWhite else AshaColors.InkSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh follow-ups",
                    tint = AshaColors.ClinicalTeal,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (statusToast != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.VerifiedEmerald,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = statusToast,
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearToast, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = AshaColors.PureWhite,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (errorBanner != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.DangerBadgeSurface,
                        border = BorderStroke(1.dp, AshaColors.DangerCrimson),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = errorBanner,
                                color = AshaColors.DangerCrimsonDark,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = AshaColors.DangerCrimsonDark,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (followUps.isEmpty()) {
                item {
                    SunlitCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No follow-ups in this filter",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Confirmed home visits and PHC danger referrals automatically populate your village follow-up queue with zero typing.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(followUps, key = { it.id }) { item ->
                    FollowUpCard(
                        item = item,
                        onMarkComplete = { onMarkComplete(item) },
                        onStartVisit = { onStartVisit(item.beneficiaryId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowUpCard(
    item: FollowUp,
    onMarkComplete: () -> Unit,
    onStartVisit: () -> Unit,
) {
    val isEscalated = item.status.equals("ESCALATED", ignoreCase = true)
    val isCompleted = item.status.equals("COMPLETED", ignoreCase = true)

    val cardBg = if (isEscalated) AshaColors.DangerSurface else AshaColors.PureWhite
    val cardBorder = if (isEscalated) AshaColors.DangerCrimson else AshaColors.CardBorder

    val (pillBg, pillFg, pillText) = when {
        isEscalated -> Triple(
            AshaColors.DangerCrimson,
            AshaColors.PureWhite,
            "URGENT PHC · ${HistoryFilterHelpers.formatIsoDateShort(item.dueDate)}",
        )
        isCompleted -> Triple(
            AshaColors.VerifiedBadgeSurface,
            AshaColors.VerifiedEmerald,
            "COMPLETED · ${HistoryFilterHelpers.formatIsoDateShort(item.dueDate)}",
        )
        else -> Triple(
            AshaColors.VoiceDiffSurface,
            AshaColors.VoiceDiffAmberDark,
            "DUE ${HistoryFilterHelpers.formatIsoDateShort(item.dueDate)}",
        )
    }

    SunlitCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = cardBg,
        borderColor = cardBorder,
        borderWidth = if (isEscalated) 2.dp else 1.5.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = pillText,
                containerColor = pillBg,
                contentColor = pillFg,
            )
            Text(
                text = item.beneficiaryId,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.beneficiaryName,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.purpose,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (item.vaccinesDue.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.vaccinesDue.forEach { vaccine ->
                    StatusPill(
                        text = vaccine,
                        containerColor = AshaColors.ClinicalTealTint,
                        contentColor = AshaColors.ClinicalTeal,
                    )
                }
            }
        }

        if (!isCompleted) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = if (isEscalated) AshaColors.DangerBorder else AshaColors.DividerSubtle,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onMarkComplete,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, AshaColors.VerifiedEmerald),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AshaColors.VerifiedEmerald,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Mark Done",
                        color = AshaColors.VerifiedEmerald,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Button(
                    onClick = onStartVisit,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.ClinicalTeal,
                        contentColor = AshaColors.PureWhite,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Start Visit",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
