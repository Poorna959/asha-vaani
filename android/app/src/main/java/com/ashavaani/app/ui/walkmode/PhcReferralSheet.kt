package com.ashavaani.app.ui.walkmode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.model.WsDangerEscalationEvent
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.theme.AshaColors

/**
 * Urgent Crimson PHC Referral Modal Sheet (PS1 Pillar #3).
 *
 * Triggered immediately on `danger_escalation`. Displays ONLY the canonical NHM danger sign
 * and localized PHC referral instruction — zero diagnosis, zero medicine/treatment advice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhcReferralSheet(
    escalation: WsDangerEscalationEvent,
    beneficiaryName: String?,
    phcPhoneNumber: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AshaColors.PureWhite,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            // Urgent Crimson Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AshaColors.DangerCrimson)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = escalation.priority.replace("_", " "),
                        containerColor = AshaColors.PureWhite,
                        contentColor = AshaColors.DangerCrimson,
                    )
                    Text(
                        text = escalation.code,
                        color = AshaColors.DangerBadgeSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = AshaColors.PureWhite,
                        modifier = Modifier.size(26.dp),
                    )
                    Text(
                        text = "Refer to PHC Immediately",
                        color = AshaColors.PureWhite,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${beneficiaryName?.takeIf { it.isNotBlank() } ?: "Beneficiary"} · ${escalation.name}",
                    color = AshaColors.DangerBadgeSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            // Body Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Localized Spoken Referral Instruction
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AshaColors.DangerSurface,
                    border = BorderStroke(1.5.dp, AshaColors.DangerBorder),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "SPOKEN PHC INSTRUCTION",
                            color = AshaColors.DangerCrimsonDark,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = escalation.localizedInstruction,
                            color = AshaColors.InkPrimary,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            ),
                        )
                    }
                }

                // Strict PS1 Zero-Diagnosis Compliance Shield
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AshaColors.SunlitCanvas,
                    border = BorderStroke(1.dp, AshaColors.CardBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = AshaColors.ClinicalTeal,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Canonical NHM List Only · Zero Diagnosis / No Medicine Advice",
                            color = AshaColors.InkSecondary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                // Primary 56dp CTA #1: Safe ACTION_DIAL to 108 / PHC
                Button(
                    onClick = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phcPhoneNumber"))
                        runCatching { context.startActivity(dialIntent) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.DangerCrimson,
                        contentColor = AshaColors.PureWhite,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Call 108 / PHC Now ($phcPhoneNumber)",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    )
                }

                // Primary 56dp CTA #2: WhatsApp / SMS Share (ACTION_SEND)
                Button(
                    onClick = {
                        val slip = WalkModeStateReducer.buildPhcReferralShareText(
                            escalation = escalation,
                            beneficiaryName = beneficiaryName,
                            phcPhone = phcPhoneNumber,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Urgent PHC Referral — ASHA Vaani")
                            putExtra(Intent.EXTRA_TEXT, slip)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share PHC Referral"))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.VerifiedEmerald,
                        contentColor = AshaColors.PureWhite,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share Referral via WhatsApp / SMS",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                // Dismiss / Confirm Flagged
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.SurfaceMuted,
                        contentColor = AshaColors.InkSecondary,
                    ),
                ) {
                    Text(
                        text = "Confirm Record Flagged & Close",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
