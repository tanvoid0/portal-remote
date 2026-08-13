package com.portalremote.ui

import android.Manifest
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.portalremote.R
import com.portalremote.data.SavedHost
import com.portalremote.data.deviceName
import com.portalremote.net.WakeOnLan
import com.portalremote.net.DiscoveredHost
import com.portalremote.net.discoverHosts
import com.portalremote.net.ipFromOctets
import com.portalremote.net.isVpnActive
import com.portalremote.net.parsePairUrl
import com.portalremote.net.requestPairing
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.portalCardBorder
import com.portalremote.ui.theme.portalCardColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private const val DEFAULT_PORT = "8765"

/** Which way in the user is currently looking at. Discovery leads: on a normal
 *  home network it needs no typing at all, and the other two are the answers to
 *  the two ways it can come up empty (broadcast blocked / different subnet). */
private enum class PairMode { Discover, Scan, Manual }

/** A PC we've asked for a token and are waiting on someone to approve. */
private data class PendingPair(val host: String, val port: Int, val label: String)

/**
 * First screen shown until a server has been paired. Three ways in, in the order
 * they cost the user anything:
 *
 * 1. **Last device** — remembered across launches, one tap, no approval needed
 *    (the token is still good).
 * 2. **Discovered PCs** — servers answering the LAN probe. Tapping one asks it for
 *    a token, which needs an Allow click on the PC; nothing secret is broadcast.
 * 3. **QR scan / typed address** — the fallbacks for when broadcast doesn't reach
 *    (an emulator behind NAT, a guest network that blocks it, another subnet).
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    savedHost: SavedHost?,
    connecting: Boolean,
    errorMessage: String?,
    onPaired: (SavedHost) -> Unit,
    onForget: () -> Unit,
    onStopReconnecting: () -> Unit,
) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(PairMode.Discover) }
    var pending by remember { mutableStateOf<PendingPair?>(null) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    var lastScanError by remember { mutableStateOf<String?>(null) }
    var scanLocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { discoverHosts().collect { discovered = it } }

    // Asking for a token holds the PC's dialog open, so it's driven from state
    // rather than a click handler: cancelling is then just `pending = null`, and
    // leaving the screen tears the request down with the composition.
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        pairError = null
        // Stop the background retry loop first, or the old PC could win the race
        // and yank the user into a session they didn't ask for mid-approval.
        onStopReconnecting()
        runCatching { requestPairing(target.host, target.port, deviceName(context)) }
            .onSuccess { pending = null; onPaired(it) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                pending = null
                pairError = failure.message ?: "Could not pair with that PC"
            }
    }

    // System bars are hidden app-wide (see MainActivity), so `safeDrawing` here is just
    // the display cutout — without it the lockup would draw under a notch.
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                title = { BrandLockup() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            savedHost?.let {
                LastDeviceCard(
                    host = it,
                    reconnecting = connecting,
                    onReconnect = { onPaired(it) },
                    onForget = onForget,
                )
            }

            val waiting = pending
            if (waiting != null) {
                WaitingForApprovalCard(label = waiting.label, onCancel = { pending = null })
            } else {
                when (mode) {
                    PairMode.Discover -> DiscoverSection(
                        // The remembered PC already has its own card above.
                        hosts = discovered.filter { it.host != savedHost?.host },
                        onPick = { pending = PendingPair(it.host, it.port, it.name) },
                    )

                    PairMode.Manual -> ManualAddressSection(
                        onConnect = { host, port -> pending = PendingPair(host, port, host) },
                    )

                    PairMode.Scan -> ScanSection(
                        scanLocked = scanLocked,
                        scanError = lastScanError,
                        onScan = { raw ->
                            runCatching { parsePairUrl(raw) }
                                .onSuccess {
                                    scanLocked = true
                                    lastScanError = null
                                    onStopReconnecting()
                                    onPaired(it)
                                }
                                .onFailure { lastScanError = "Not a Portal Remote QR code" }
                        },
                    )
                }

                ModeSwitch(mode = mode, onModeChange = { mode = it; pairError = null })
            }

            val message = pairError ?: errorMessage
            val pairStatus = when {
                message != null -> PairStatus.Error
                connecting && savedHost == null -> PairStatus.Connecting
                else -> null
            }
            pairStatus?.let { StatusBanner(status = it, errorMessage = message) }

            // A VPN on the phone looks exactly like a dead PC: discovery finds
            // nothing and every connection times out, while the PC itself is fine
            // and its firewall is innocent. Say so here rather than let the user go
            // looking on the other machine. Re-checked whenever the screen has
            // something to complain about, since the user may toggle it and come back.
            val stuck = message != null || discovered.isEmpty()
            val onVpn = remember(stuck, message) { stuck && isVpnActive(context) }
            if (onVpn) VpnNotice()

            // A rejected pairing must not leave the scanner permanently locked.
            errorMessage?.let { LaunchedEffect(it) { scanLocked = false } }
        }
    }
}

/** Shown when the phone can't reach a PC and a VPN is the likeliest reason. Worded
 *  as the two things that actually fix it, since "a VPN is active" on its own reads
 *  as an observation rather than an instruction. */
@Composable
private fun VpnNotice() {
    Text(
        "A VPN is on. It routes this phone's traffic away from your local network, " +
            "so a PC on the same Wi-Fi can still be unreachable. Turn the VPN off, " +
            "or allow local network access in its settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// ---------------------------------------------------------------- sections ----

@Composable
private fun DiscoverSection(hosts: List<DiscoveredHost>, onPick: (DiscoveredHost) -> Unit) {
    SectionLabel("PCs on this network")

    if (hosts.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                "Looking for PCs running Portal Remote…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    hosts.forEach { host ->
        DeviceCard(
            title = host.name,
            subtitle = "${host.host}:${host.port}",
            onClick = { onPick(host) },
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanSection(scanLocked: Boolean, scanError: String?, onScan: (String) -> Unit) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // Requested on entering this mode rather than on launch: scanning is now the
    // fallback, and a camera prompt in front of a screen that doesn't need one
    // reads as the app overreaching.
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    Text(
        "Open Portal Remote on your PC and scan the code in its window.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )

    when {
        cameraPermission.status.isGranted -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                QrScannerView(modifier = Modifier.fillMaxSize()) { raw ->
                    if (!scanLocked) onScan(raw)
                }
                ScanSuccessOverlay(visible = scanLocked)
            }
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        cameraPermission.status.shouldShowRationale -> {
            Text("Camera access is needed to scan the pairing code.", textAlign = TextAlign.Center)
            Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text("Grant camera access")
            }
        }

        else -> CircularProgressIndicator()
    }
}

@Composable
private fun ManualAddressSection(onConnect: (String, Int) -> Unit) {
    SectionLabel("Address shown on the PC")
    IpEntry(onConnect = onConnect)
}

/** The three ways in, minus whichever one you're already on. Each carries its icon:
 *  these are the fallbacks someone reaches for when the easy path didn't work, and a
 *  row of same-length blue words is the hardest thing on this screen to pick from at
 *  a glance. */
@Composable
private fun ModeSwitch(mode: PairMode, onModeChange: (PairMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mode != PairMode.Discover) {
            ModeButton(Icons.Filled.Search, "Search again") { onModeChange(PairMode.Discover) }
        }
        if (mode != PairMode.Scan) {
            ModeButton(Icons.Filled.QrCodeScanner, "Scan QR code") { onModeChange(PairMode.Scan) }
        }
        if (mode != PairMode.Manual) {
            // A dialpad, not a keyboard: the address field really is digit boxes.
            ModeButton(Icons.Filled.Dialpad, "Type address") { onModeChange(PairMode.Manual) }
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

// ------------------------------------------------------------------- cards ----

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The remembered PC. One tap and it's a session again — the saved token means
 *  nobody has to be at the PC to approve anything. */
@Composable
private fun LastDeviceCard(
    host: SavedHost,
    reconnecting: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = portalCardColors(),
        border = portalCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Last used",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(host.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${host.host}:${host.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onReconnect, enabled = !reconnecting) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (reconnecting) "Reconnecting…" else "Reconnect")
                }
                if (reconnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(modifier = Modifier.weight(1f))
                // Link/link-off as a pair, and the same LinkOff Settings and the
                // dead-session dialog use — "this pairing goes away" is one idea and
                // should look like one wherever it's offered.
                TextButton(onClick = onForget) {
                    Icon(
                        Icons.Filled.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Forget")
                }
            }
            // Only once the PC has told us its MAC. Wake-on-LAN is the one thing that
            // works when it isn't answering, which is exactly when this card is what
            // you are looking at.
            host.mac?.let { WakeButton(mac = it, peer = host.host, label = host.label) }
        }
    }
}

/**
 * A magic packet, and honesty about it — `docs/phase4-casting.md` §8. Nothing comes
 * back from a sleeping PC, so this can only report that the packet left the phone; if
 * the machine doesn't come up, the fix is in its BIOS and adapter settings and the
 * text has to say so rather than leave the user pressing a button that "did nothing".
 */
@Composable
private fun WakeButton(mac: String, peer: String, label: String) {
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    TextButton(onClick = { scope.launch { sent = WakeOnLan.wake(mac, peer) } }) {
        Icon(
            Icons.Filled.PowerSettingsNew,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        Text("Wake $label")
    }
    if (sent) {
        Text(
            "Sent. If it doesn't wake, Wake-on-LAN has to be enabled in the PC's BIOS " +
                "and on its network adapter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = portalCardColors(),
        border = portalCardBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The pause while someone walks to the PC. Says exactly what to look for there —
 *  an unexplained spinner is the fastest way to make this flow feel broken. */
@Composable
private fun WaitingForApprovalCard(label: String, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = portalCardColors(),
        border = portalCardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("Waiting for $label", style = MaterialTheme.typography.titleMedium)
            Text(
                "Portal Remote is asking on that PC. Choose Allow there to finish pairing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// -------------------------------------------------------------- ip entry ----

/**
 * An address is only ever digits, so the keyboard should only ever be digits:
 * four octet boxes and a port, focus hopping on its own the way an OTP field
 * does. Nobody hunts for the "." key, and a wrong octet is one box to fix rather
 * than a re-typed string.
 */
@Composable
private fun IpEntry(onConnect: (String, Int) -> Unit) {
    val octets = remember { mutableStateListOf("", "", "", "") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_PORT) }
    val octetFocus = remember { List(4) { FocusRequester() } }
    val portFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { octetFocus[0].requestFocus() }

    fun onOctetChange(index: Int, raw: String) {
        // A "." (or anything else non-numeric the IME offers) means "next box" —
        // typing the separator out of habit shouldn't cost anything.
        val separator = raw.any { !it.isDigit() }
        val digits = raw.filter { it.isDigit() }.take(3)
        if ((digits.toIntOrNull() ?: 0) > 255) return // 256+ isn't an octet; drop the keystroke
        octets[index] = digits

        if (!separator && digits.length < 3) return
        if (index < 3) octetFocus[index + 1].requestFocus() else portFocus.requestFocus()
    }

    val ip = ipFromOctets(octets)
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(4) { index ->
                DigitBox(
                    value = octets[index],
                    onValueChange = { onOctetChange(index, it) },
                    onBackspaceWhenEmpty = { if (index > 0) octetFocus[index - 1].requestFocus() },
                    focusRequester = octetFocus[index],
                    imeAction = if (index < 3) ImeAction.Next else ImeAction.Done,
                    onImeAction = {
                        if (index < 3) octetFocus[index + 1].requestFocus() else portFocus.requestFocus()
                    },
                    modifier = Modifier.weight(1f),
                )
                if (index < 3) {
                    Text(".", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Port",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DigitBox(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                onBackspaceWhenEmpty = { octetFocus[3].requestFocus() },
                focusRequester = portFocus,
                imeAction = ImeAction.Done,
                onImeAction = { if (ip != null && portNumber != null) onConnect(ip, portNumber) },
                modifier = Modifier.width(96.dp),
            )
        }

        Button(
            onClick = { if (ip != null && portNumber != null) onConnect(ip, portNumber) },
            enabled = ip != null && portNumber != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}

/** One box of the address field. `BasicTextField` rather than `OutlinedTextField`:
 *  the stock field's minimum width and internal padding are both wider than a
 *  three-digit box, and neither is settable from the public API. */
@Composable
private fun DigitBox(
    value: String,
    onValueChange: (String) -> Unit,
    onBackspaceWhenEmpty: () -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // The box has to say two things a stock field says for free: that it is a control at
    // all (a filled `surface-raised` box edged in the hairline `border` token was white
    // on white in light mode, at 1.2:1 — below WCAG 1.4.11's 3:1 for a control boundary),
    // and which of the five has the caret. `borderStrong` answers the first, the accent
    // edge the second.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        interactionSource = interaction,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        modifier = modifier
            .height(56.dp)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                val backspaceOnEmpty = event.type == KeyEventType.KeyDown &&
                    event.key == Key.Backspace &&
                    value.isEmpty()
                if (backspaceOnEmpty) onBackspaceWhenEmpty()
                backspaceOnEmpty
            },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        PortalRemoteTheme.extendedColors.surfaceMuted,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) colors.primary else PortalRemoteTheme.extendedColors.borderStrong,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}

// ------------------------------------------------------------------ chrome ----

/** Mark + wordmark in the app bar — see docs/design-system.md §11. Pairing is the
 * only screen that isn't yet "inside" a session, so it's the one place the app
 * names itself; the task itself is stated by the section labels below it, which
 * keeps this from costing vertical space the device list needs. */
@Composable
private fun BrandLockup() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_portal_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text("Portal Remote", modifier = Modifier.padding(start = 12.dp))
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
    //
    // The fill was `error` at 12% and the text was `error` on top of it: a red on a wash
    // of the same red, which measured 3.95:1 in light mode — under AA for body text, on
    // the one card in this flow whose whole job is to be read. `errorContainer` is the
    // token for exactly this and its `on-` pair is 8:1 in both themes.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Error, contentDescription = null)
            Text(message, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
