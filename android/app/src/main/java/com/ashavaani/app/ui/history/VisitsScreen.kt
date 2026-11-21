package com.ashavaani.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.model.VisitRecord
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import com.ashavaani.app.ui.theme.ClinicalValueTextStyle

/**
 * Saved Home Visits History Tab (Tab 4).
 * Local-first Room cache with `All Visits` / `PHC Escalated Only` filter chips and red danger badges.
 */
@Composable
fun VisitsScreen(
    visits: List<VisitRecord>,
    activeFilter: VisitFilter,
    onSelectFilter: (VisitFilter) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        // Filter Chips + Refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisitFilter.entries.forEach { filter ->
                    val selected = filter == activeFilter
                    val activeBg = if (filter == VisitFilter.ESCALATED) {
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
                            text = if (filter == VisitFilter.ESCALATED) "🚨 ${filter.label}" else filter.label,
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
                    contentDescription = "Refresh visits",
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
            if (visits.isEmpty()) {
                item {
                    SunlitCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (activeFilter == VisitFilter.ESCALATED) {
                                "No PHC escalated visits recorded"
                            } else {
                                "No saved home visits yet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Confirm a visit by voice in Walk Mode to save it locally and sync with the server.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(visits, key = { it.id }) { visit ->
                    VisitHistoryCard(visit = visit)
                }
            }
        }
    }
}

@Composable
private fun VisitHistoryCard(visit: VisitRecord) {
    val isFlagged = visit.hasDangerSign || visit.dangerSigns.isNotEmpty()

    SunlitCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isFlagged) AshaColors.DangerCrimson else AshaColors.CardBorder,
        borderWidth = if (isFlagged) 2.dp else 1.5.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = visit.beneficiaryName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = visit.visitType,
                containerColor = if (isFlagged) AshaColors.DangerBadgeSurface else AshaColors.ClinicalTealTint,
                contentColor = if (isFlagged) AshaColors.DangerCrimson else AshaColors.ClinicalTeal,
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${HistoryFilterHelpers.formatIsoDateShort(visit.createdAt)} · ${visit.id} · ${visit.languageCode}",
            style = MaterialTheme.typography.bodySmall,
        )

        if (isFlagged) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = AshaColors.DangerBadgeSurface,
                border = BorderStroke(1.dp, AshaColors.DangerBorder),
            ) {
                Text(
                    text = "🚨 FLAGGED PHC REFERRAL: ${visit.dangerSigns.joinToString(", ").ifBlank { "Danger Sign" }}",
                    color = AshaColors.DangerCrimsonDark,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Compact 3-Column Tabular Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VisitSummaryCell(
                label = "WEIGHT",
                value = visit.weightKg?.let { "$it kg" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            VisitSummaryCell(
                label = "IFA",
                value = visit.ifaTabletsGiven?.let { "$it goli" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            VisitSummaryCell(
                label = "NEXT VISIT",
                value = HistoryFilterHelpers.formatIsoDateShort(visit.nextVisitDate),
                modifier = Modifier.weight(1f),
            )
        }

        if (visit.vaccinationsDue.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "VACCINES:",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                )
                visit.vaccinationsDue.forEach { vaccine ->
                    StatusPill(
                        text = vaccine,
                        containerColor = AshaColors.ClinicalTeal,
                        contentColor = AshaColors.PureWhite,
                    )
                }
            }
        }

        if (visit.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = visit.notes,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VisitSummaryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = AshaColors.SunlitCanvas,
        border = BorderStroke(1.dp, AshaColors.DividerSubtle),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = ClinicalValueTextStyle.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
