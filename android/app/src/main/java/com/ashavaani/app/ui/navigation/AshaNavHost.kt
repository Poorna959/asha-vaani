package com.ashavaani.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ashavaani.app.model.ConnectionState
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.components.TelemetryStatusPill
import com.ashavaani.app.ui.theme.AshaColors

/**
 * Root 4-tab application shell matching `docs/UI_UX_Design_Phase_P2.md`.
 * Individual tab screens will be plugged in during P2-04 (Walk Mode), P2-05 (Registers), and P2-06 (Follow-Ups & Visits).
 */
@Composable
fun AshaVaaniAppShell(
    connectionState: ConnectionState = ConnectionState.OFFLINE_CACHE,
    ttfaMs: Int? = null,
    gnaniMode: String = "MOCK",
    pendingFollowUpCount: Int = 0,
    onOpenSettings: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AshaTab.WALK_MODE.route

    val currentTab = AshaTab.entries.firstOrNull { it.route == currentRoute } ?: AshaTab.WALK_MODE

    Scaffold(
        containerColor = AshaColors.SunlitCanvas,
        topBar = {
            AshaTopBar(
                title = if (currentTab == AshaTab.WALK_MODE) "ASHA Vaani" else currentTab.label,
                subtitle = currentTab.subtitle,
                connectionState = connectionState,
                ttfaMs = ttfaMs,
                gnaniMode = gnaniMode,
                onTelemetryClick = onOpenSettings,
            )
        },
        bottomBar = {
            AshaBottomNavBar(
                currentRoute = currentRoute,
                pendingFollowUpCount = pendingFollowUpCount,
                onSelectTab = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AshaTab.WALK_MODE.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(AshaTab.WALK_MODE.route) {
                TabScaffoldPlaceholder(
                    tag = "P2-04 · WALK MODE",
                    title = "Hands-Free Voice Register Ready",
                    description = "Continuous 16kHz PCM voice capture with hardware AEC/NS/AGC, native Indic language pills, Amber FieldDiff voice corrections, and urgent Crimson PHC referral escalation.",
                )
            }
            composable(AshaTab.REGISTERS.route) {
                TabScaffoldPlaceholder(
                    tag = "P2-05 · CUSTOM REGISTERS",
                    title = "Custom First-Visit Registers (FR-7)",
                    description = "Create custom named registers, search patients by name, add patients by voice (FR-7.2), inspect clean mobile Field : Answer profiles (zero Excel grids), and export .xlsx/.csv via FileProvider.",
                )
            }
            composable(AshaTab.FOLLOW_UPS.route) {
                TabScaffoldPlaceholder(
                    tag = "P2-06 · FOLLOW-UP QUEUE",
                    title = "Village Follow-Up Queue",
                    description = "Automatically populated when visits are confirmed or flagged for urgent PHC referral. Supports 1-tap Mark Done and Start Visit.",
                )
            }
            composable(AshaTab.VISITS.route) {
                TabScaffoldPlaceholder(
                    tag = "P2-06 · SAVED VISITS",
                    title = "Saved Home Visits History",
                    description = "Local-first Room cache of confirmed ANC, Newborn HBNC, and Immunisation visits with All and PHC Escalated filters.",
                )
            }
        }
    }
}

@Composable
private fun AshaTopBar(
    title: String,
    subtitle: String,
    connectionState: ConnectionState,
    ttfaMs: Int?,
    gnaniMode: String,
    onTelemetryClick: () -> Unit,
) {
    Surface(
        color = AshaColors.PureWhite,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AshaColors.ClinicalTeal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "अ",
                            color = AshaColors.PureWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                TelemetryStatusPill(
                    connectionState = connectionState,
                    ttfaMs = ttfaMs,
                    gnaniMode = gnaniMode,
                    onClick = onTelemetryClick,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = AshaColors.DividerSubtle)
        }
    }
}

@Composable
private fun AshaBottomNavBar(
    currentRoute: String,
    pendingFollowUpCount: Int,
    onSelectTab: (AshaTab) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = AshaColors.CardBorder)
        NavigationBar(
            containerColor = AshaColors.PureWhite,
            tonalElevation = 0.dp,
        ) {
            AshaTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        if (tab == AshaTab.FOLLOW_UPS && pendingFollowUpCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = AshaColors.DangerCrimson,
                                        contentColor = AshaColors.PureWhite,
                                    ) {
                                        Text(
                                            text = if (pendingFollowUpCount > 99) "99+" else pendingFollowUpCount.toString(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                )
                            }
                        } else {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                            )
                        }
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.sp,
                            ),
                            maxLines = 1,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AshaColors.ClinicalTeal,
                        selectedTextColor = AshaColors.ClinicalTeal,
                        indicatorColor = AshaColors.ClinicalTealTint,
                        unselectedIconColor = AshaColors.InkMuted,
                        unselectedTextColor = AshaColors.InkMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TabScaffoldPlaceholder(
    tag: String,
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SunlitCard(modifier = Modifier.fillMaxWidth()) {
            StatusPill(
                text = tag,
                containerColor = AshaColors.ClinicalTealTint,
                contentColor = AshaColors.ClinicalTeal,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
