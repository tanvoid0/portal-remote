package com.portalremote.ui

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalremote.data.AppSettings
import com.portalremote.data.DeckItem
import com.portalremote.data.SavedHost
import com.portalremote.net.ActiveWindow
import com.portalremote.net.AiCatalog
import com.portalremote.net.AiState
import com.portalremote.net.CastState
import com.portalremote.net.CastStatus
import com.portalremote.net.CastTarget
import com.portalremote.net.ChatTurn
import com.portalremote.net.NowPlaying
import com.portalremote.net.PcStats
import com.portalremote.net.PowerTimerState
import com.portalremote.net.Protocol
import com.portalremote.net.ServerHello
import com.portalremote.net.ShareEntry
import com.portalremote.net.Volume
import com.portalremote.ui.theme.HudPulse
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.SystemBars
import com.portalremote.ui.theme.accentGlow
import com.portalremote.ui.theme.rememberPressScale
import kotlin.math.absoluteValue
import org.json.JSONObject

/** Slowest a dropped-input buzz may repeat. Long enough that a held gesture answers
 *  once rather than continuously, short enough that a second deliberate tap is still
 *  told no. */
private const val REJECT_INTERVAL_MS = 1_000L

private enum class RemoteTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Filled.TouchApp),
    BROWSER("Browser", Icons.Filled.Public),
    MONITOR("Monitor", Icons.Filled.Monitor),
    TRANSFER("Transfer", Icons.Filled.SwapVert),
    DECK("Deck", Icons.Filled.Apps),
    ASSISTANT("Assistant", Icons.Filled.AutoAwesome),
}

/**
 * Post-pairing shell: bottom nav between the six things this app does. Everything you
 * drive the PC with by hand — trackpad, keyboard, media, TV remote — lives behind the
 * one Control tab and sends through the same [send] callback into the live socket;
 * files and share talk to the server's HTTP endpoints directly using [host].
 *
 * Six slots, not eight: Monitor holds the mirror and the stats dashboard, Transfer holds
 * share and files, each behind a 48dp sub-tab row (see [PortalSubTabRow]). That's what
 * left room for Deck (quick search, launched apps, shortcuts) at the six-icon limit
 * docs/design-system.md §13 sets — a capsule with more in it is a strip of targets too
 * narrow to hit rather than a way of navigating.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(
    hello: ServerHello,
    host: SavedHost,
    reconnecting: Boolean = false,
    /** Why the session is over for good, if it is — a rejected token. Distinct from
     *  [reconnecting], which is the recoverable kind and fixes itself. */
    failure: String? = null,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    send: (JSONObject) -> Unit,
    nowPlaying: NowPlaying?,
    cast: CastState?,
    castStatus: CastStatus?,
    castTargets: List<CastTarget>,
    castTarget: String?,
    castScanning: Boolean,
    onCastTarget: (String?) -> Unit,
    onScanCastTargets: () -> Unit,
    powerTimer: PowerTimerState?,
    volume: Volume?,
    shares: List<ShareEntry>,
    onCastFile: (Uri) -> String?,
    stats: PcStats?,
    statsHistory: List<PcStats>,
    /** Starts and stops the PC sampling itself. Called by the Stats screen as it is
     *  composed and disposed, so the PC only does the work while it is being watched. */
    onWatchStats: (Boolean) -> Unit,
    deckItems: List<DeckItem>,
    onDeckItemsChange: (List<DeckItem>) -> Unit,
    /** The PC's foreground app, for Deck's context row — null until watched, same
     *  subscribe-on-open shape as [stats]/[onWatchStats]. */
    activeWindow: ActiveWindow?,
    onWatchActiveWindow: (Boolean) -> Unit,
    aiState: AiState?,
    chat: List<ChatTurn>,
    chatStreaming: Boolean,
    aiCatalog: AiCatalog?,
    aiCatalogLoading: Boolean,
    aiCatalogError: String?,
    onProbeAi: (retry: Boolean) -> Unit,
    onSendChat: (String) -> Unit,
    onConfirmPlan: (turnId: String, approved: List<Int>) -> Unit,
    onCancelPlan: (turnId: String) -> Unit,
    onRegenerateChat: () -> Unit,
    onStopChat: () -> Unit,
    onClearChat: () -> Unit,
    onLoadAiCatalog: () -> Unit,
    onSelectAiModel: (provider: String?, model: String) -> Unit,
    onShareText: (String) -> Unit,
    onShareUri: (Uri) -> Unit,
    onRetryShare: (Long) -> Unit,
    onForget: () -> Unit,
) {
    // Saveable for the same reason ControlScreen's mode is: a rotation on the mirror
    // otherwise lands you back on the trackpad, and "which of five things am I doing"
    // is not state the user expects a turn of the phone to decide.
    var tab by rememberSaveable { mutableStateOf(RemoteTab.CONTROL) }
    // Held here, above the tab crossfade: a browser that reloads every page because
    // you glanced at the trackpad is not a browser you would use.
    val browser = remember { BrowserSession() }
    DisposableEffect(Unit) { onDispose { browser.destroyAll() } }
    val haptics = LocalHaptics.current
    var showSettings by remember { mutableStateOf(false) }
    // Keyed on the reason, so a *second*, different failure speaks up again rather
    // than inheriting the first one's dismissal.
    var dismissedFailure by remember(failure) { mutableStateOf(false) }
    // Owned here rather than in ScreenScreen because it's the shell's own chrome that
    // has to go: the mirror is a picture of another screen, and a title row plus a nav
    // bar around it is a picture of another screen in a frame.
    var fullscreen by remember { mutableStateOf(false) }
    if (fullscreen) BackHandler { fullscreen = false }
    // Status bar stays up everywhere except here — the one screen whose content is
    // itself a screen, per docs/design-system.md §13.
    SystemBars(immersive = fullscreen)

    // While the socket is down `WsClient.send` drops every message on the floor — by
    // design, a stale pointer delta is worse than a skipped one — but the surfaces
    // below still tick, tint and echo as if it landed. §6a's whole argument is that
    // the eyes are on the PC, so a confirmation for something that never arrived is
    // the one lie this app can tell. Swap it for `reject()`, rate-limited because a
    // 120Hz move stream would otherwise be a rattle rather than an answer.
    var lastReject by remember { mutableLongStateOf(0L) }
    val gatedSend: (JSONObject) -> Unit = { json ->
        if (!reconnecting) {
            send(json)
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastReject > REJECT_INTERVAL_MS) {
                lastReject = now
                haptics.reject()
            }
        }
    }

    // Transfer's two lists run their content *under* the nav bar so it can be glass over
    // something (§2 rule 4). The control surfaces deliberately don't: a trackpad whose
    // bottom 56dp belongs to the chrome is a trackpad with a dead strip, and the mirror
    // is a picture — sliding it under a blur crops it for decoration. The stats
    // dashboard sits out too: it is a column of cards, and frosting the bottom card is
    // an unreadable number rather than depth.
    val underGlass = tab == RemoteTab.TRANSFER

    // Recorded only while a tab actually passes under the bar, so the trackpad and the
    // mirror — the two surfaces that redraw constantly — never pay for a backdrop
    // nothing can see.
    val backdrop = rememberGraphicsLayer()
    val glass = underGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // A session is one long gesture on a single surface, which the system idle
    // timer doesn't reliably count as activity — opt-in, since holding the screen
    // awake costs battery.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Six tab icons between a text field and the keyboard is chrome nobody can use
    // while typing — the bar's own bottom inset would float it right there, on top of
    // the IME. It goes away instead, and the content takes the keyboard's inset itself
    // (see contentWindowInsets below, which Scaffold only applies once no bottom bar
    // is measured).
    val imeVisible = WindowInsets.isImeVisible

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // The top inset is claimed by RemoteTitleBar itself (its own background runs
            // up under the status bar, per §13); the content here only needs the
            // horizontal side of it, for a landscape notch — plus the bottom, which is
            // what keeps a composer above the keyboard once the nav bar has stepped out
            // of the way.
            contentWindowInsets = WindowInsets.safeDrawing
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            // The strip the floating capsule sits in belongs to the Scaffold rather than
            // to the screen above it, so it has to be the canvas colour — otherwise
            // every screen keeps a band of a slightly different grey across its bottom
            // edge, under and around the bar.
            containerColor = PortalRemoteTheme.hud.background,
            topBar = {
                if (!fullscreen) {
                    RemoteTitleBar(
                        deviceName = hello.name,
                        reconnecting = reconnecting,
                        failure = failure,
                        onSettings = { showSettings = true },
                    )
                }
            },
            bottomBar = {
                if (!fullscreen && !imeVisible) {
                    RemoteNavBar(
                        selected = tab,
                        backdrop = if (glass) backdrop else null,
                        // Only on an actual change: re-tapping the current tab does
                        // nothing, so it shouldn't feel like it did.
                        onSelect = { entry -> if (tab != entry) haptics.tick(); tab = entry },
                    )
                }
            },
        ) { padding ->
            val dir = LocalLayoutDirection.current
            // Cross-fade only, no slide — a tab switch should read as switching, not
            // navigating. See docs/design-system.md §6.
            Crossfade(
                targetState = tab,
                animationSpec = tween(Motion.TabSwitchDurationMs, easing = Motion.EaseOut),
                modifier = Modifier
                    .padding(
                        start = padding.calculateStartPadding(dir),
                        end = padding.calculateEndPadding(dir),
                        top = padding.calculateTopPadding(),
                        // Dropped on the glass tabs — that gap *is* what the bar floats
                        // over. Those screens re-apply it to their own list and FAB.
                        bottom = if (underGlass) 0.dp else padding.calculateBottomPadding(),
                    )
                    .fillMaxSize()
                    .then(
                        if (glass) {
                            Modifier.drawWithContent {
                                backdrop.record { this@drawWithContent.drawContent() }
                                drawLayer(backdrop)
                            }
                        } else {
                            Modifier
                        },
                    ),
                label = "remote-tab",
            ) { selected ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selected) {
                        RemoteTab.CONTROL -> ControlScreen(
                            host = host,
                            settings = settings,
                            nowPlaying = nowPlaying,
                            onMove = { dx, dy -> gatedSend(Protocol.mouseMove(dx, dy)) },
                            onScroll = { dy -> gatedSend(Protocol.scroll(dy = dy)) },
                            onClick = { button, down -> gatedSend(Protocol.mouseClick(button, down)) },
                            onText = { s -> gatedSend(Protocol.text(s)) },
                            onTap = { key -> gatedSend(Protocol.tap(key)) },
                            onCombo = { keys -> gatedSend(Protocol.combo(*keys.toTypedArray())) },
                            onMedia = { action -> gatedSend(Protocol.media(action)) },
                            onSeek = { ms -> gatedSend(Protocol.seek(ms)) },
                            cast = cast,
                            castStatus = castStatus,
                            castTargets = castTargets,
                            castTarget = castTarget,
                            castScanning = castScanning,
                            onCastTarget = onCastTarget,
                            onScanCastTargets = onScanCastTargets,
                            onCast = { url -> gatedSend(Protocol.cast(url, target = castTarget)) },
                            onCastFile = onCastFile,
                            onPlayer = gatedSend,
                            onPower = { mode -> gatedSend(Protocol.power(mode)) },
                            powerTimer = powerTimer,
                            onPowerTimerSet = { mode, seconds ->
                                gatedSend(Protocol.powerTimerSet(mode, seconds))
                            },
                            onPowerTimerCancel = { gatedSend(Protocol.powerTimerCancel()) },
                            volume = volume,
                            onSetVolume = { level -> gatedSend(Protocol.volumeSet(level)) },
                        )
                        RemoteTab.BROWSER -> BrowserScreen(
                            session = browser,
                            cast = cast,
                            castStatus = castStatus,
                            // Same rule as the Media tab: an unknown target is one of
                            // the PC's own routes, and those all seek.
                            castSeekable = castTargets
                                .firstOrNull { t -> t.id == cast?.target }?.seek ?: true,
                            // The browser's cast button obeys the same chosen screen as
                            // the Media tab's — one choice, not one per entry point.
                            onCast = { url, title -> gatedSend(Protocol.cast(url, title, castTarget)) },
                            onPlayer = gatedSend,
                            onOpenOnDesktop = { url -> gatedSend(Protocol.openUrl(url)) },
                        )
                        RemoteTab.MONITOR -> MonitorScreen(
                            host = host,
                            settings = settings,
                            fullscreen = fullscreen,
                            onFullscreen = { fullscreen = it },
                            onPresetChange = { preset ->
                                onSettingsChange { it.copy(mirrorPreset = preset.name) }
                            },
                            send = gatedSend,
                            stats = stats,
                            statsHistory = statsHistory,
                            onWatchStats = onWatchStats,
                        )
                        RemoteTab.TRANSFER -> TransferScreen(
                            host = host,
                            shares = shares,
                            onShareText = onShareText,
                            onShareUri = onShareUri,
                            onRetryShare = onRetryShare,
                            bottomInset = padding.calculateBottomPadding(),
                        )
                        RemoteTab.DECK -> DeckScreen(
                            items = deckItems,
                            onItemsChange = onDeckItemsChange,
                            send = gatedSend,
                            activeWindow = activeWindow,
                            onWatchActiveWindow = onWatchActiveWindow,
                        )
                        RemoteTab.ASSISTANT -> AssistantScreen(
                            state = aiState,
                            chat = chat,
                            streaming = chatStreaming,
                            catalog = aiCatalog,
                            catalogLoading = aiCatalogLoading,
                            catalogError = aiCatalogError,
                            onProbe = onProbeAi,
                            onSend = onSendChat,
                            onConfirm = onConfirmPlan,
                            onCancelPlan = onCancelPlan,
                            onRegenerate = onRegenerateChat,
                            onStop = onStopChat,
                            onClear = onClearChat,
                            onLoadCatalog = onLoadAiCatalog,
                            onSelectModel = onSelectAiModel,
                        )
                    }
                }
            }
        }

        // Full screen takes the title row with it, and with it the one thing that says
        // the socket is down — on the screen where a frozen picture is the *expected*
        // look between frames. Only here: anywhere else the title row already has it,
        // and a second copy would be chrome saying the same thing twice.
        if (reconnecting && fullscreen) {
            Text(
                failure ?: "Reconnecting…",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // A rejected token ends the session, and the shell can't say that in a status
        // dot — every surface under it still looks usable. So it's stated once, up
        // front, with the only action that fixes it. Dismissible on purpose: the
        // screen behind still holds whatever the user was in the middle of, the title
        // row keeps the reason in `danger`, and Settings has the same way out.
        if (failure != null && !dismissedFailure) {
            AlertDialog(
                onDismissRequest = { dismissedFailure = true },
                icon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                title = { Text("${hello.name} ended this session") },
                text = {
                    Text(
                        "$failure. That usually means the pairing token was rotated on " +
                            "the PC, or Portal Remote was reinstalled there. Pairing " +
                            "again is the only way back.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = onForget) { Text("Pair again") }
                },
                dismissButton = {
                    TextButton(onClick = { dismissedFailure = true }) { Text("Not now") }
                },
            )
        }

        // Settings covers the shell instead of replacing it: navigating away and back
        // would tear down every tab — the folder Files is showing, an upload in
        // flight — which is the same state PortalRemoteApp's single RemoteScreen call
        // site exists to protect through a reconnect.
        if (showSettings) {
            BackHandler { showSettings = false }
            Surface(modifier = Modifier.fillMaxSize()) {
                SettingsScreen(
                    settings = settings,
                    hello = hello,
                    host = host,
                    onSettingsChange = onSettingsChange,
                    onForget = onForget,
                    onBack = { showSettings = false },
                )
            }
        }
    }
}

/** A floating capsule, hand-rolled rather than Material3's `NavigationBar`: the stock
 * bar is an 80dp opaque slab pinned to the bottom edge with four permanent labels, and
 * its selection indicator appears and disappears in place instead of travelling, so a
 * switch reads as two separate blinks.
 *
 * This one detaches from the edge, and the selected tab is the only one that spells
 * itself out — its slot widens and the label slides in beside the icon, so the bar
 * carries exactly the one word that is currently true. That is the same trade §13 made
 * by dropping all four labels, one step further: the width the labels used to cost is
 * spent only on the tab you are on, and the unselected three stay icons (with the label
 * on `contentDescription`, and `selectable(role = Tab)` giving TalkBack the selected
 * state the stock item provided).
 *
 * Per docs/design-system.md §6/§13; nothing at all animates under the system "remove
 * animations" setting.
 *
 * [backdrop] is the content layer to frost behind the bar, or null for a solid bar —
 * see the `underGlass`/`glass` pair at the RemoteScreen call site for who gets which
 * and why. */
@Composable
private fun RemoteNavBar(
    selected: RemoteTab,
    backdrop: GraphicsLayer?,
    onSelect: (RemoteTab) -> Unit,
) {
    val tabs = RemoteTab.entries
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val spec: FiniteAnimationSpec<Float> = if (reduced) snap() else Motion.navIndicatorSpec()
    val slot by animateFloatAsState(
        targetValue = tabs.indexOf(selected).toFloat(),
        animationSpec = spec,
        label = "nav-pill",
    )
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    // Over glass the bar's own colour is a tint on top of the frosted content, not the
    // surface itself — opaque here would make the blur invisible.
    val fill = PortalRemoteTheme.hud.panel.copy(alpha = if (backdrop != null) 0.78f else 1f)
    val capsule = RoundedCornerShape(percent = 50)
    // How far the capsule's bottom edge sits above the window's — its own margin plus
    // whatever the cutout asked for. The frost has to reach past it to sample the
    // content down there.
    val gap = 10.dp + WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            // A floating bar costs its own margin, which §13 counts as chrome. It buys
            // the shape: a capsule pinned to the bottom edge is a rectangle with two
            // rounded corners, and the content passing *beside* it is what sells the
            // glass as glass.
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .height(60.dp)
            // Accent-tinted rather than black (see Modifier.accentGlow): a black shadow
            // under a near-black bar in dark mode is nothing at all, and the capsule is
            // the one piece of chrome that should read as floating over the content
            // rather than sitting in it.
            .accentGlow(accent.copy(alpha = 0.55f), capsule, 14.dp)
            .clip(capsule)
            // A pale capsule on a pale list has no edge of its own; the hairline is what
            // keeps the shape from dissolving in light mode, and on dark it does the
            // opposite job — a lit edge, which is what every panel under it carries.
            .border(1.dp, PortalRemoteTheme.hud.edge, capsule),
    ) {
        if (backdrop != null) {
            // The frost is its own node so the blur lands on the copied content and
            // not on the icons: `renderEffect` applies to everything a layer draws,
            // and the icons are drawn by siblings of this one. 48px because a lighter
            // blur leaves list rows legible enough to read, and text competing with
            // the tab icons is worse than no glass at all.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(48f, 48f, TileMode.Decal)
                        clip = true
                    }
                    .drawBehind {
                        // The bar no longer reaches the window's bottom edge, so the
                        // offset carries its own margin as well as the height above it.
                        translate(top = -(backdrop.size.height - size.height - gap.toPx())) {
                            drawLayer(backdrop)
                        }
                    },
            )
        }
        Box(modifier = Modifier.matchParentSize().background(fill))

        Row(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, entry ->
                val isSelected = entry == selected
                val interaction = remember { MutableInteractionSource() }
                val press by rememberPressScale(interaction)
                // Every item reads its own state off the indicator's position rather
                // than off `selected`, which is what makes a two-tab jump stagger: the
                // one in the middle swells and brightens as the selection passes over
                // it, then settles back. One number drives width, tint and the label's
                // fade, so the wake can't drift out of sync with what causes it.
                val near = 1f - (slot - index).coerceIn(-1f, 1f).absoluteValue
                Row(
                    modifier = Modifier
                        // The one animation in the app that is deliberately a layout
                        // pass, not a transform (§6): the point of the pill is that it
                        // *makes room* for the word, and a scaled-up capsule with
                        // squashed text in it is the cheap imitation of that.
                        .weight(1f + 1.5f * near)
                        .fillMaxHeight()
                        .clip(capsule)
                        .background(accent.copy(alpha = 0.16f * near))
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(entry) },
                            role = Role.Tab,
                            interactionSource = interaction,
                            // The pill and the icon are the feedback; a ripple on top
                            // of both is a third thing happening for one tap.
                            indication = null,
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = entry.label,
                        tint = lerp(idle, accent, near),
                        modifier = Modifier.graphicsLayer {
                            scaleX = press
                            scaleY = press
                        },
                    )
                    // Composed only past the halfway point, where the slot is already
                    // wide enough to hold it — a label measured into a 55dp slot would
                    // shove the icon out of its own pill on the way in.
                    if (near > 0.5f) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .graphicsLayer { alpha = (near - 0.5f) * 2f },
                        )
                    }
                }
            }
        }
    }
}

/** Device name + a small status dot + the settings gear, on one 44dp line — a stock
 * `TopAppBar` is 64dp and this row carries one short string. Per docs/design-system.md
 * §7. RemoteScreen stays mounted through a brief control-socket blip (see
 * [reconnecting]) so screen state — current Files folder, an in-flight upload —
 * survives instead of bouncing the user back to PairScreen every time the socket
 * hiccups; "Connected" is left to the dot's color, and only the *abnormal* state spells
 * itself out. The dot morphs color over 200ms ease-in-out rather than snapping, per
 * §6's "connect/disconnect status change" spec — gated behind the reduced-motion check
 * per §6/§9, an instant swap instead when the system "remove animations" setting is
 * on. */
@Composable
private fun RemoteTitleBar(
    deviceName: String,
    reconnecting: Boolean,
    failure: String?,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val spec: AnimationSpec<Color> = if (Motion.reducedMotionEnabled(context)) {
        snap()
    } else {
        tween(Motion.StatusMorphDurationMs, easing = Motion.EaseInOut)
    }
    val dotColor by animateColorAsState(
        // Three states, not two: warning is "wait", danger is "this will not fix
        // itself". Showing a rejected token as amber would tell the user to keep
        // waiting for something that is never coming back.
        targetValue = when {
            failure != null -> MaterialTheme.colorScheme.error
            reconnecting -> PortalRemoteTheme.extendedColors.warning
            else -> PortalRemoteTheme.extendedColors.success
        },
        animationSpec = spec,
        label = "status-dot",
    )
    val borderColor = PortalRemoteTheme.hud.edge

    // The hairline is the only thing separating this row from the screen under it in
    // light mode, where both are white.
    Surface(
        color = PortalRemoteTheme.hud.panel,
        // drawWithContent, not drawBehind: a modifier passed to Surface wraps its own
        // background, so anything drawn behind here would be painted straight over.
        modifier = Modifier.drawWithContent {
            drawContent()
            val line = 1.dp.toPx()
            drawRect(
                color = borderColor,
                topLeft = Offset(0f, size.height - line),
                size = Size(size.width, line),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The status bar draws over this padding, not above it — same colour,
                // same row, so the clock/battery read as part of the app's own chrome
                // rather than a system strip sitting on top of it (§13).
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .height(44.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bloomed rather than a flat fill — the one reading this row exists to give,
            // drawn with the same live-dot instrument the stats dashboard uses.
            HudPulse(color = dotColor, dot = 8.dp)
            Text(
                deviceName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Weighted, so the gear stays pinned right and a long PC name
                // ellipsizes rather than pushing it off the edge.
                modifier = Modifier.weight(1f),
            )
            if (reconnecting) {
                Text(
                    failure ?: "Reconnecting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failure != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
}
