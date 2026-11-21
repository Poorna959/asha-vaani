package com.ashavaani.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ashavaani.app.AppContainer
import com.ashavaani.app.model.ConnectionState
import com.ashavaani.app.ui.components.TelemetryStatusPill
import com.ashavaani.app.ui.history.FollowUpScreen
import com.ashavaani.app.ui.history.HistoryViewModel
import com.ashavaani.app.ui.history.VisitsScreen
import com.ashavaani.app.ui.registers.PatientProfileScreen
import com.ashavaani.app.ui.registers.RegisterDetailScreen
import com.ashavaani.app.ui.registers.RegistersScreen
import com.ashavaani.app.ui.registers.RegistersViewModel
import com.ashavaani.app.ui.theme.AshaColors
import com.ashavaani.app.ui.walkmode.SettingsSheet
import com.ashavaani.app.ui.walkmode.WalkInputMode
import com.ashavaani.app.ui.walkmode.WalkModeScreen
import com.ashavaani.app.ui.walkmode.WalkModeViewModel

/**
 * Complete 4-tab ASHA Vaani application shell wiring:
 * - Tab 1: Walk Mode (Home) + Urgent PHC Referral Modal Sheet + Settings/Telemetry HUD
 * - Tab 2: Custom Registers (List -> Detail + Search + Export -> Field:Answer Profile)
 * - Tab 3: Follow-Up Queue (PHC Urgent pinned + 1-tap Mark Done / Start Visit)
 * - Tab 4: Saved Visits History (All / PHC Escalated Only)
 */
@Composable
fun AshaVaaniAppShell(
    container: AppContainer,
) {
    val walkModeViewModel: WalkModeViewModel = viewModel(
        factory = WalkModeViewModel.factory(container),
    )
    val registersViewModel: RegistersViewModel = viewModel(
        factory = RegistersViewModel.factory(container),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container),
    )

    val walkUiState by walkModeViewModel.uiState.collectAsStateWithLifecycle()
    val pendingFollowUpCount by walkModeViewModel.openFollowUpCount.collectAsStateWithLifecycle()

    val registers by registersViewModel.registers.collectAsStateWithLifecycle()
    val patientCounts by registersViewModel.patientCounts.collectAsStateWithLifecycle()
    val patientsInRegister by registersViewModel.patientsInSelectedRegister.collectAsStateWithLifecycle()
    val registerSearchQuery by registersViewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedProfile by registersViewModel.selectedProfile.collectAsStateWithLifecycle()
    val registerBusy by registersViewModel.isBusy.collectAsStateWithLifecycle()
    val registerToast by registersViewModel.statusToast.collectAsStateWithLifecycle()
    val registerError by registersViewModel.errorBanner.collectAsStateWithLifecycle()

    val followUps by historyViewModel.filteredFollowUps.collectAsStateWithLifecycle()
    val followUpFilter by historyViewModel.followUpFilter.collectAsStateWithLifecycle()
    val visits by historyViewModel.filteredVisits.collectAsStateWithLifecycle()
    val visitFilter by historyViewModel.visitFilter.collectAsStateWithLifecycle()
    val historyToast by historyViewModel.statusToast.collectAsStateWithLifecycle()
    val historyError by historyViewModel.errorBanner.collectAsStateWithLifecycle()

    var showSettingsSheet by remember { mutableStateOf(false) }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AshaTab.WALK_MODE.route

    val activeTab = when {
        currentRoute.startsWith("register_detail") || currentRoute.startsWith("patient_profile") -> AshaTab.REGISTERS
        else -> AshaTab.entries.firstOrNull { it.route == currentRoute } ?: AshaTab.WALK_MODE
    }

    val (topTitle, topSubtitle) = when {
        currentRoute == AshaTab.WALK_MODE.route -> {
            "ASHA Vaani" to if (walkUiState.inputMode == WalkInputMode.REGISTER_INTAKE) {
                "Voice Intake · FR-7.2"
            } else {
                "Walk Mode · Hands-Free"
            }
        }
        currentRoute.startsWith("register_detail") -> {
            val regId = backStackEntry?.arguments?.getString("registerId")
            val reg = registers.firstOrNull { it.id == regId }
            (reg?.name ?: "Register Detail") to "${patientsInRegister.size} patients · ${reg?.fields?.size ?: 0} fields"
        }
        currentRoute.startsWith("patient_profile") -> {
            (selectedProfile?.patientName ?: "Patient Profile") to "Field : Answer Profile"
        }
        else -> activeTab.label to activeTab.subtitle
    }

    fun navigateToWalkMode() {
        navController.navigate(AshaTab.WALK_MODE.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AshaColors.SunlitCanvas,
        topBar = {
            AshaTopBar(
                title = topTitle,
                subtitle = topSubtitle,
                connectionState = walkUiState.connectionState,
                ttfaMs = walkUiState.metrics.ttfaMs,
                gnaniMode = walkUiState.gnaniMode,
                onTelemetryClick = { showSettingsSheet = true },
            )
        },
        bottomBar = {
            AshaBottomNavBar(
                activeTab = activeTab,
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
                WalkModeScreen(
                    uiState = walkUiState,
                    hapticEvents = walkModeViewModel.hapticEvents,
                    onMicClick = walkModeViewModel::onMicButtonPressed,
                    onSelectLanguage = walkModeViewModel::selectLanguage,
                    onResetDraft = walkModeViewModel::resetCurrentDraft,
                    onClearFollowUpContext = walkModeViewModel::clearFollowUpContext,
                    onExitRegisterIntake = walkModeViewModel::exitRegisterIntake,
                    onToggleSimDrawer = walkModeViewModel::toggleSimDrawer,
                    onSendSimulatedUtterance = walkModeViewModel::sendSimulatedUtterance,
                    onDismissDanger = walkModeViewModel::dismissDangerEscalation,
                    onClearToast = walkModeViewModel::clearToast,
                    onClearError = walkModeViewModel::clearError,
                )
            }

            composable(AshaTab.REGISTERS.route) {
                RegistersScreen(
                    registers = registers,
                    patientCounts = patientCounts,
                    statusToast = registerToast,
                    errorBanner = registerError,
                    onRefresh = registersViewModel::refreshAll,
                    onOpenRegister = { reg ->
                        registersViewModel.openRegister(reg.id)
                        navController.navigate(AshaSubRoutes.registerDetail(reg.id))
                    },
                    onCreateRegister = { name, desc, fields ->
                        registersViewModel.createRegister(name, desc, fields)
                    },
                    onClearToast = registersViewModel::clearToast,
                    onClearError = registersViewModel::clearError,
                )
            }

            composable(
                route = AshaSubRoutes.REGISTER_DETAIL,
                arguments = listOf(navArgument("registerId") { type = NavType.StringType }),
            ) { entry ->
                val registerId = entry.arguments?.getString("registerId").orEmpty()
                val register = registers.firstOrNull { it.id == registerId }

                LaunchedEffect(registerId) {
                    if (registerId.isNotBlank()) {
                        registersViewModel.openRegister(registerId)
                    }
                }

                RegisterDetailScreen(
                    register = register,
                    patients = patientsInRegister,
                    searchQuery = registerSearchQuery,
                    isBusy = registerBusy,
                    errorBanner = registerError,
                    onBack = { navController.popBackStack() },
                    onSearchChange = registersViewModel::updateSearchQuery,
                    onOpenPatient = { patient ->
                        registersViewModel.loadPatientProfile(patient.id)
                        navController.navigate(AshaSubRoutes.patientProfile(registerId, patient.id))
                    },
                    onAddPatientByVoice = { reg ->
                        walkModeViewModel.startRegisterVoiceIntake(
                            registerId = reg.id,
                            registerName = reg.name,
                            expectedFields = reg.fields.sortedBy { it.orderIndex }.map { it.fieldName },
                        )
                        navigateToWalkMode()
                    },
                    onAddManualPatient = { regId, name, category, answers ->
                        registersViewModel.addManualPatient(regId, name, category, answers)
                    },
                    onExportRegister = { regId, format, exportsDir, onReady ->
                        registersViewModel.exportRegister(regId, format, exportsDir, onReady)
                    },
                    onClearError = registersViewModel::clearError,
                )
            }

            composable(
                route = AshaSubRoutes.PATIENT_PROFILE,
                arguments = listOf(
                    navArgument("registerId") { type = NavType.StringType },
                    navArgument("patientId") { type = NavType.StringType },
                ),
            ) { entry ->
                val patientId = entry.arguments?.getString("patientId").orEmpty()

                LaunchedEffect(patientId) {
                    if (patientId.isNotBlank()) {
                        registersViewModel.loadPatientProfile(patientId)
                    }
                }

                PatientProfileScreen(
                    profile = selectedProfile?.takeIf { it.patientId == patientId } ?: selectedProfile,
                    isBusy = registerBusy,
                    onBack = { navController.popBackStack() },
                    onStartFollowUpVisit = { pid ->
                        walkModeViewModel.startPatientFollowUp(pid)
                        navigateToWalkMode()
                    },
                    onUpdatePatient = { pid, name, category, answers ->
                        registersViewModel.updatePatientFields(pid, name, category, answers)
                    },
                )
            }

            composable(AshaTab.FOLLOW_UPS.route) {
                FollowUpScreen(
                    followUps = followUps,
                    activeFilter = followUpFilter,
                    statusToast = historyToast,
                    errorBanner = historyError,
                    onSelectFilter = historyViewModel::setFollowUpFilter,
                    onRefresh = historyViewModel::refreshAll,
                    onMarkComplete = historyViewModel::markFollowUpCompleted,
                    onStartVisit = { beneficiaryId ->
                        walkModeViewModel.startPatientFollowUp(beneficiaryId)
                        navigateToWalkMode()
                    },
                    onClearToast = historyViewModel::clearToast,
                    onClearError = historyViewModel::clearError,
                )
            }

            composable(AshaTab.VISITS.route) {
                VisitsScreen(
                    visits = visits,
                    activeFilter = visitFilter,
                    onSelectFilter = historyViewModel::setVisitFilter,
                    onRefresh = historyViewModel::refreshAll,
                )
            }
        }
    }

    if (showSettingsSheet) {
        SettingsSheet(
            uiState = walkUiState,
            onSaveAndReconnect = walkModeViewModel::saveSettingsAndReconnect,
            onDismiss = { showSettingsSheet = false },
        )
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
    activeTab: AshaTab,
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
                val selected = activeTab == tab
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
