package com.portalremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.portalremote.net.InvalidPairUrl
import com.portalremote.net.hostFromManualEntry
import com.portalremote.net.parsePairUrl
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import android.Manifest

/**
 * First screen shown until a server has been paired: camera QR scan, with a manual
 * host/token entry fallback for the emulator (which cannot reach a LAN QR code
 * anyway, since it sits behind NAT at 10.0.2.2) or a camera-less device.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    connecting: Boolean,
    errorMessage: String?,
    onPaired: (com.portalremote.data.SavedHost) -> Unit,
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var lastScanError by remember { mutableStateOf<String?>(null) }
    var scanLocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair with your PC") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Open Portal Remote on your PC and scan the QR code from its tray icon.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!manualMode) {
                when {
                    cameraPermission.status.isGranted -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            QrScannerView(modifier = Modifier.fillMaxSize()) { raw ->
                                if (scanLocked) return@QrScannerView
                                runCatching { parsePairUrl(raw) }
                                    .onSuccess {
                                        scanLocked = true
                                        lastScanError = null
                                        onPaired(it)
                                    }
                                    .onFailure { lastScanError = "Not a Portal Remote QR code" }
                            }
                            ScanSuccessOverlay(visible = scanLocked)
                        }
                        lastScanError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    cameraPermission.status.shouldShowRationale -> {
                        Text(
                            "Camera access is needed to scan the pairing code.",
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                            Text("  Grant camera access")
                        }
                    }
                    else -> {
                        CircularProgressIndicator()
                    }
                }

                TextButton(onClick = { manualMode = true }) {
                    Text("Enter address manually instead")
                }
            } else {
                ManualEntryForm(onPaired = onPaired)
                TextButton(onClick = { manualMode = false }) {
                    Text("Scan QR code instead")
                }
            }

            val pairStatus = when {
                errorMessage != null -> PairStatus.Error
                connecting -> PairStatus.Connecting
                else -> null
            }
            pairStatus?.let { StatusBanner(status = it, errorMessage = errorMessage) }

            // A rejected pairing must not leave the scanner permanently locked.
            errorMessage?.let { LaunchedEffect(it) { scanLocked = false } }
        }
    }
}

/** The one moment worth a little bounce — see docs/design-system.md §6. */
@Composable
private fun ScanSuccessOverlay(visible: Boolean) {
    val context = LocalContext.current
    val spec: FiniteAnimationSpec<Float> =
        if (Motion.reducedMotionEnabled(context)) snap() else Motion.pairingSuccessSpec()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spec) + scaleIn(animationSpec = spec, initialScale = 0f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = PortalRemoteTheme.extendedColors.success,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

private enum class PairStatus { Connecting, Error }

/** Connecting/error cross-fade instead of two separately-popping cards — this is
 * the one Android surface where a connect/disconnect-style status stays mounted
 * across the transition (RemoteScreen's status dot can't: see its doc comment),
 * so it's where §6's "color + icon morph, never abrupt" rule actually applies. */
@Composable
private fun StatusBanner(status: PairStatus, errorMessage: String?) {
    val context = LocalContext.current
    val spec: FiniteAnimationSpec<Float> =
        if (Motion.reducedMotionEnabled(context)) snap() else Motion.statusMorphSpec()

    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
        modifier = Modifier.fillMaxWidth(),
        label = "pair-status",
    ) { s ->
        when (s) {
            PairStatus.Connecting -> ConnectingCard()
            PairStatus.Error -> ErrorCard(errorMessage ?: "")
        }
    }
}

@Composable
private fun ConnectingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Connecting…", modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    // "danger" has no extended-token slot of its own: it's mapped onto Material3's
    // error/onError, same as the rest of the app (PairScreen's inline error texts,
    // FilesScreen, etc.) — see ui/theme/Theme.kt.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(message, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ManualEntryForm(onPaired: (com.portalremote.data.SavedHost) -> Unit) {
    var hostPort by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = hostPort,
            onValueChange = { hostPort = it },
            label = { Text("Host:port (e.g. 192.168.0.21:8765)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Pairing token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                runCatching { hostFromManualEntry(hostPort, token) }
                    .onSuccess { error = null; onPaired(it) }
                    .onFailure { error = (it as? InvalidPairUrl)?.message ?: "Invalid address" }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}
