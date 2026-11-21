package com.ashavaani.app.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.auth.AshaAuthSession
import com.ashavaani.app.auth.SupabaseAuthClient
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import kotlinx.coroutines.launch

/**
 * Sunlit Material 3 Authentication Screen powered by Supabase Auth (`ulznnflntffpuxxmjdip`):
 * - 1-Tap "Continue with Gmail / Google" (in-app Gmail sign-in + Supabase Google OAuth browser flow)
 * - Email & Password Sign In / Create Account tabs
 * - 1-Tap "Continue as Field ASHA (Offline / Judge Mode)" so rural dead zones never lock out workers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    supabaseAuth: SupabaseAuthClient,
    onAuthenticated: (AshaAuthSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSignUpMode by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showGmailSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Brand Hero Header
        SunlitCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = AshaColors.PureWhite,
            borderColor = AshaColors.ClinicalTealBorder,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = AshaColors.ClinicalTeal,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "ASHA Vaani",
                            tint = AshaColors.PureWhite,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "ASHA Vaani",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        )
                        StatusPill(
                            text = "PS1 · HEALTHCARE",
                            containerColor = AshaColors.ClinicalTealTint,
                            contentColor = AshaColors.ClinicalTeal,
                        )
                    }
                    Text(
                        text = "Zero-Typing Voice Field Register for ASHA Workers",
                        style = MaterialTheme.typography.bodySmall,
                        color = AshaColors.InkSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = AshaColors.DividerSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Sign in with your Gmail or Email account via Supabase to sync your village registers, ANC/HBNC visit notes, and PHC danger-sign escalations.",
                style = MaterialTheme.typography.bodyMedium,
                color = AshaColors.InkSecondary,
            )
        }

        // Primary Authentication Card
        SunlitCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "WORKER AUTHENTICATION · SUPABASE",
                style = MaterialTheme.typography.labelSmall,
                color = AshaColors.ClinicalTeal,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isSignUpMode) "Create ASHA Worker Account" else "Welcome back, ASHA Didi",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Primary 1-Tap Gmail / Google Sign-In Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !isLoading) {
                        errorMessage = null
                        showGmailSheet = true
                    },
                shape = RoundedCornerShape(14.dp),
                color = AshaColors.PureWhite,
                border = BorderStroke(1.5.dp, AshaColors.ClinicalTeal),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFEA4335),
                        modifier = Modifier.size(26.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "G",
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Continue with Gmail / Google",
                        color = AshaColors.InkPrimary,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = AshaColors.DividerSubtle)
                Text(
                    text = "OR USE EMAIL & PASSWORD",
                    style = MaterialTheme.typography.labelSmall,
                    color = AshaColors.InkMuted,
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = AshaColors.DividerSubtle)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sign In / Create Account Mode Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AshaColors.SurfaceMuted)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(false to "Sign In", true to "Create Account").forEach { (signUp, label) ->
                    val selected = isSignUpMode == signUp
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                isSignUpMode = signUp
                                errorMessage = null
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) AshaColors.PureWhite else Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (selected) AshaColors.ClinicalTeal else AshaColors.InkSecondary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AshaColors.DangerBadgeSurface,
                    border = BorderStroke(1.dp, AshaColors.DangerCrimson),
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = AshaColors.DangerCrimsonDark,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (isSignUpMode) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("ASHA Worker Full Name") },
                    placeholder = { Text("e.g. Kavitha Devi") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Person, contentDescription = null)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email or Gmail Address *") },
                placeholder = { Text("asha.worker@gmail.com") },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Email, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (min 6 chars) *") },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Lock, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    scope.launch {
                        val result = if (isSignUpMode) {
                            supabaseAuth.signUpWithEmail(email, password, fullName)
                        } else {
                            supabaseAuth.signInWithEmail(email, password)
                        }
                        isLoading = false
                        result.onSuccess { session ->
                            onAuthenticated(session)
                        }.onFailure { err ->
                            errorMessage = err.message ?: "Authentication failed. Please check your credentials."
                        }
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AshaColors.ClinicalTeal,
                    contentColor = AshaColors.PureWhite,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = AshaColors.PureWhite,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = if (isSignUpMode) "Create Account & Enter Walk Mode" else "Sign In with Email",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                )
            }
        }

        // Offline Rural & Hackathon Judge Quick-Access Card
        SunlitCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = AshaColors.VerifiedSurface,
            borderColor = AshaColors.VerifiedBorder,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RURAL OFFLINE / HACKATHON JUDGE DEMO",
                        style = MaterialTheme.typography.labelSmall,
                        color = AshaColors.VerifiedEmerald,
                    )
                    Text(
                        text = "Instant 1-Tap Field ASHA Access",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Enter Walk Mode immediately without typing in zero-connectivity areas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AshaColors.InkSecondary,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = {
                        onAuthenticated(
                            AshaAuthSession(
                                userId = "ASHA-FIELD-01",
                                email = "asha.field@nhm.gov.in",
                                displayName = "ASHA Field Worker",
                                provider = "field_demo",
                                accessToken = "offline_field_session",
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.VerifiedEmerald,
                        contentColor = AshaColors.PureWhite,
                    ),
                ) {
                    Text("Enter App", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showGmailSheet) {
        GmailSignInSheet(
            supabaseAuth = supabaseAuth,
            onSuccess = { session ->
                showGmailSheet = false
                onAuthenticated(session)
            },
            onOpenBrowserGoogleOAuth = {
                val url = supabaseAuth.buildGoogleOAuthUrl()
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
            onDismiss = { showGmailSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GmailSignInSheet(
    supabaseAuth: SupabaseAuthClient,
    onSuccess: (AshaAuthSession) -> Unit,
    onOpenBrowserGoogleOAuth: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var gmailInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val quickGmailPresets = listOf(
        "asha.sunita@gmail.com" to "Sunita ASHA",
        "lakshmi.phc@gmail.com" to "Lakshmi Gowda (ASHA)",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AshaColors.PureWhite,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFEA4335),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "G",
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                            )
                        }
                    }
                    Text(text = "Sign in with Gmail", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            Text(
                text = "Enter your @gmail.com address or tap a verified ASHA Gmail account below:",
                style = MaterialTheme.typography.bodySmall,
                color = AshaColors.InkSecondary,
            )

            // 1-Tap Quick Gmail Account Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quickGmailPresets.forEach { (presetEmail, presetName) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    supabaseAuth.signInWithGmail(presetEmail, presetName)
                                        .onSuccess(onSuccess)
                                        .onFailure { error = it.message }
                                    busy = false
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = AshaColors.SunlitCanvas,
                        border = BorderStroke(1.dp, AshaColors.CardBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(text = presetName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = presetEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AshaColors.InkSecondary,
                                )
                            }
                            StatusPill(
                                text = "1-Tap Gmail",
                                containerColor = AshaColors.ClinicalTealTint,
                                contentColor = AshaColors.ClinicalTeal,
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    color = AshaColors.DangerCrimsonDark,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                )
            }

            OutlinedTextField(
                value = gmailInput,
                onValueChange = { gmailInput = it },
                label = { Text("Your Gmail Address (@gmail.com) *") },
                placeholder = { Text("yourname@gmail.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Display Name (optional)") },
                placeholder = { Text("ASHA Worker Name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        supabaseAuth.signInWithGmail(gmailInput, nameInput)
                            .onSuccess(onSuccess)
                            .onFailure { error = it.message ?: "Invalid Gmail address" }
                        busy = false
                    }
                },
                enabled = !busy && gmailInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AshaColors.ClinicalTeal,
                    contentColor = AshaColors.PureWhite,
                ),
            ) {
                Text("Continue with Gmail", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = onOpenBrowserGoogleOAuth,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Open Google OAuth in Browser (Supabase)", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
