package com.qtone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtone.app.update.UpdateInstaller
import com.qtone.app.update.UpdateManifest

/**
 * Renders the "Update available" dialog over the rest of the UI.
 *
 * Three visual phases inside the same composable:
 *   1. Prompt: shows version + changelog, [Update Now] and [Later] buttons.
 *   2. Downloading: shows a progress bar reflecting [UpdateInstaller.progress].
 *      The [Later] button is hidden during download.
 *   3. Failed: progress=-1f → shows error text with a Retry / Later option.
 *
 * The dialog uses a fixed-size centered card on a translucent black scrim.
 * Tapping outside the card does NOT dismiss (the scrim has no click handler)
 * — the user must use the Back button (non-mandatory updates only) or click
 * Later. This avoids accidental dismissal via D-pad over-navigation.
 *
 * Focus is grabbed by the primary action button when the dialog appears
 * so the user can press OK / Enter immediately without first moving focus.
 *
 * @param isMandatory when true, hides the Later button and absorbs Back to
 * prevent dismissal (handled by [BackInterceptor] inside the dialog).
 */
@Composable
fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    isMandatory: Boolean,
    onDismiss: () -> Unit,
    onAcceptUpdate: () -> Unit
) {
    val downloading by UpdateInstaller.inProgress.collectAsState()
    val progress by UpdateInstaller.progress.collectAsState()
    val downloadFailed = progress < 0f
    val primaryFocusRequester = remember { FocusRequester() }

    // Try to autofocus the primary button when the dialog enters. The
    // focusRequester() can fail if the underlying view isn't ready yet, so
    // we wrap in try-catch to be safe.
    LaunchedEffect(Unit) {
        try { primaryFocusRequester.requestFocus() } catch (_: Throwable) {}
    }

    // Block back on mandatory updates. The user must accept or close the app.
    if (isMandatory) {
        androidx.activity.compose.BackHandler(enabled = true) { /* swallow */ }
    } else {
        androidx.activity.compose.BackHandler(enabled = !downloading) { onDismiss() }
    }

    // Scrim
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF101015),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = Modifier
                .widthIn(min = 480.dp, max = 640.dp)
                .padding(24.dp)
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    "Update available",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Version ${manifest.versionName} is available.",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))

                if (!manifest.changelog.isNullOrBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIfReasonable()
                            .background(Color(0xFF1A1A22), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            manifest.changelog,
                            color = Color(0xDDFFFFFF),
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }

                when {
                    downloading -> {
                        Text(
                            "Downloading update… ${(progress * 100).toInt()}%",
                            color = Color(0xCCFFFFFF),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Color.White,
                            trackColor = Color(0x33FFFFFF)
                        )
                    }
                    downloadFailed -> {
                        Text(
                            "Download failed. Check your connection and try again.",
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DialogButton(
                                text = "Retry",
                                primary = true,
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                onClick = onAcceptUpdate
                            )
                            if (!isMandatory) {
                                DialogButton(
                                    text = "Later",
                                    primary = false,
                                    onClick = onDismiss
                                )
                            }
                        }
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DialogButton(
                                text = "Update Now",
                                primary = true,
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                onClick = onAcceptUpdate
                            )
                            if (!isMandatory) {
                                DialogButton(
                                    text = "Later",
                                    primary = false,
                                    onClick = onDismiss
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val baseColor = if (primary) Color.White else Color.Transparent
    val borderColor = if (primary) Color.Transparent else Color(0x66FFFFFF)
    val textColor = when {
        primary -> Color(0xFF0A0A0E)
        focused -> Color.White
        else -> Color(0xCCFFFFFF)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = baseColor,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (focused) Color.White else borderColor
        )
    ) {
        Box(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
            Text(
                text,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Caps the changelog box height to something reasonable. Long changelogs
 * scroll inside this box; short ones render at their natural height.
 */
@Composable
private fun Modifier.heightIfReasonable(): Modifier =
    this.then(Modifier.height(140.dp))
