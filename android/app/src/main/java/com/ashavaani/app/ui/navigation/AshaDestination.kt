package com.ashavaani.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level 4-tab destinations for ASHA Vaani's one-thumb outdoor bottom navigation bar.
 */
enum class AshaTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val subtitle: String,
) {
    WALK_MODE(
        route = "walk_mode",
        label = "Walk Mode",
        icon = Icons.Rounded.Mic,
        subtitle = "Walk Mode · Hands-Free",
    ),
    REGISTERS(
        route = "registers",
        label = "Registers",
        icon = Icons.AutoMirrored.Rounded.MenuBook,
        subtitle = "Custom Field Registers",
    ),
    FOLLOW_UPS(
        route = "follow_ups",
        label = "Follow-Ups",
        icon = Icons.Rounded.CheckCircle,
        subtitle = "Auto-Scheduled Queue",
    ),
    VISITS(
        route = "visits",
        label = "Visits",
        icon = Icons.Rounded.History,
        subtitle = "Saved Home Visits",
    ),
}

/**
 * Sub-routes inside the Registers tab (Register Detail & Field:Answer Patient Profile).
 */
object AshaSubRoutes {
    const val REGISTER_DETAIL = "register_detail/{registerId}"
    const val PATIENT_PROFILE = "patient_profile/{registerId}/{patientId}"

    fun registerDetail(registerId: String): String = "register_detail/$registerId"
    fun patientProfile(registerId: String, patientId: String): String =
        "patient_profile/$registerId/$patientId"
}
