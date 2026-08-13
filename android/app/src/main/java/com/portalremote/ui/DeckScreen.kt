package com.portalremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalremote.data.DeckItem
import com.portalremote.net.ActiveWindow
import com.portalremote.net.Protocol
import com.portalremote.ui.theme.HudPanel
import com.portalremote.ui.theme.HudPanelShape
import com.portalremote.ui.theme.HudType
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.hudCanvas
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What a tile does. Rides on [DeckItem.type] by name — docs/design-system.md §11 rule 3,
 * "the glyph rides on the enum" — so a new type can't ship without a label the editor can
 * show.
 */
internal enum class DeckActionType(val label: String) {
    RUN("Launch"),
    WEB_SEARCH("Web search"),
    PC_SEARCH("PC search"),
    SHORTCUT("Shortcut"),
    POWER("Power"),
    MEDIA("Media"),
}

/** Every glyph a tile can carry. A closed set, not a free icon font picker: the editor
 *  is a row of buttons, not a search box, and twenty-odd covers everything a quick-access
 *  tile is for. */
internal enum class DeckIcon(val icon: ImageVector) {
    SEARCH(Icons.Filled.Search),
    PUBLIC(Icons.Filled.Public),
    FOLDER(Icons.Filled.Folder),
    TERMINAL(Icons.Filled.Terminal),
    DESKTOP(Icons.Filled.DesktopWindows),
    GRID(Icons.Filled.GridView),
    TASKS(Icons.Filled.BarChart),
    CAMERA(Icons.Filled.PhotoCamera),
    LOCK(Icons.Filled.Lock),
    SLEEP(Icons.Filled.Bedtime),
    RESTART(Icons.Filled.RestartAlt),
    SHUTDOWN(Icons.Filled.PowerSettingsNew),
    MUTE(Icons.AutoMirrored.Filled.VolumeOff),
    VOLUME_UP(Icons.AutoMirrored.Filled.VolumeUp),
    VOLUME_DOWN(Icons.AutoMirrored.Filled.VolumeDown),
    PLAY(Icons.Filled.PlayArrow),
    NEXT(Icons.Filled.SkipNext),
    PREV(Icons.Filled.SkipPrevious),
    STOP(Icons.Filled.Stop),
    APPS(Icons.Filled.Apps),
    KEYS(Icons.Filled.Keyboard),
    SETTINGS(Icons.Filled.Settings),
    COPY(Icons.Filled.ContentCopy),
    PASTE(Icons.Filled.ContentPaste),
    UNDO(Icons.AutoMirrored.Filled.Undo),
    PRINT(Icons.Filled.Print);

    companion object {
        /** Unrecognised (a downgrade reading a newer save) falls back to the generic
         *  tile glyph rather than crashing the grid. */
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: APPS
    }
}

private data class ShortcutPreset(val label: String, val icon: DeckIcon, val keys: List<String>)

/** Every combo here is built from [com.portalremote.input.InputActions]' named-key table —
 *  no punctuation keys (Win+. for the emoji panel, say) exist in it, so none are offered. */
private val SHORTCUT_PRESETS = listOf(
    ShortcutPreset("Show Desktop", DeckIcon.DESKTOP, listOf("win", "d")),
    ShortcutPreset("Task View", DeckIcon.GRID, listOf("win", "tab")),
    ShortcutPreset("Task Manager", DeckIcon.TASKS, listOf("ctrl", "shift", "esc")),
    ShortcutPreset("Screenshot", DeckIcon.CAMERA, listOf("win", "shift", "s")),
    ShortcutPreset("Alt+Tab", DeckIcon.GRID, listOf("alt", "tab")),
    ShortcutPreset("Copy", DeckIcon.COPY, listOf("ctrl", "c")),
    ShortcutPreset("Paste", DeckIcon.PASTE, listOf("ctrl", "v")),
    ShortcutPreset("Undo", DeckIcon.UNDO, listOf("ctrl", "z")),
    ShortcutPreset("Print", DeckIcon.PRINT, listOf("ctrl", "p")),
)

private data class PowerPreset(val mode: String, val label: String, val icon: DeckIcon, val destructive: Boolean)

private val POWER_PRESETS = listOf(
    PowerPreset("lock", "Lock", DeckIcon.LOCK, destructive = false),
    PowerPreset("sleep", "Sleep", DeckIcon.SLEEP, destructive = false),
    PowerPreset("restart", "Restart", DeckIcon.RESTART, destructive = true),
    PowerPreset("shutdown", "Shut down", DeckIcon.SHUTDOWN, destructive = true),
)

private val DESTRUCTIVE_POWER = POWER_PRESETS.filter { it.destructive }.map { it.mode }.toSet()

private data class MediaPreset(val action: String, val label: String, val icon: DeckIcon)

private val MEDIA_PRESETS = listOf(
    MediaPreset("play_pause", "Play/Pause", DeckIcon.PLAY),
    MediaPreset("next", "Next", DeckIcon.NEXT),
    MediaPreset("prev", "Previous", DeckIcon.PREV),
    MediaPreset("stop", "Stop", DeckIcon.STOP),
    MediaPreset("mute", "Mute", DeckIcon.MUTE),
    MediaPreset("vol_up", "Volume Up", DeckIcon.VOLUME_UP),
    MediaPreset("vol_down", "Volume Down", DeckIcon.VOLUME_DOWN),
)

private data class ContextAction(val label: String, val keys: List<String>)

/** Touch-Bar-style row: what a tile grid can't do, since these follow whatever window
 *  has focus on the PC rather than sitting still. Windows has no per-app extensibility
 *  API for this the way macOS does — there's no registry an app publishes "its" bar
 *  actions to — so this is a fixed catalog keyed by executable name, built from the
 *  shortcuts each app already answers to. Not yet user-editable; see DeckScreen's
 *  ContextPanel. */
private val FALLBACK_CONTEXT_ACTIONS = listOf(
    ContextAction("Save", listOf("ctrl", "s")),
    ContextAction("Copy", listOf("ctrl", "c")),
    ContextAction("Paste", listOf("ctrl", "v")),
    ContextAction("Close", listOf("alt", "f4")),
)

private val BROWSER_CONTEXT_ACTIONS = listOf(
    ContextAction("New Tab", listOf("ctrl", "t")),
    ContextAction("Close Tab", listOf("ctrl", "w")),
    ContextAction("Reload", listOf("f5")),
    ContextAction("Find", listOf("ctrl", "f")),
)

/** Keyed on the bare process name — `"chrome"`, not `"chrome.exe"` — matching what
 *  `Process.ProcessName` already reports on the server, so nothing has to strip an
 *  extension on either side. */
private val CONTEXT_ACTIONS: Map<String, List<ContextAction>> = mapOf(
    "explorer" to listOf(
        ContextAction("New Folder", listOf("ctrl", "shift", "n")),
        ContextAction("Rename", listOf("f2")),
        ContextAction("Properties", listOf("alt", "enter")),
        ContextAction("Refresh", listOf("f5")),
    ),
    "chrome" to BROWSER_CONTEXT_ACTIONS,
    "msedge" to BROWSER_CONTEXT_ACTIONS,
    "firefox" to BROWSER_CONTEXT_ACTIONS,
    "code" to listOf(
        ContextAction("Save", listOf("ctrl", "s")),
        ContextAction("Command Palette", listOf("ctrl", "shift", "p")),
        ContextAction("New File", listOf("ctrl", "n")),
        ContextAction("Find", listOf("ctrl", "f")),
    ),
    "notepad" to listOf(
        ContextAction("Save", listOf("ctrl", "s")),
        ContextAction("Find", listOf("ctrl", "f")),
        ContextAction("Select All", listOf("ctrl", "a")),
    ),
    "winword" to listOf(
        ContextAction("Save", listOf("ctrl", "s")),
        ContextAction("Bold", listOf("ctrl", "b")),
        ContextAction("Find", listOf("ctrl", "f")),
    ),
)

private fun contextActionsFor(process: String): List<ContextAction> =
    CONTEXT_ACTIONS[process.lowercase()] ?: FALLBACK_CONTEXT_ACTIONS

/** How long the PC's Run box / Start search needs on screen before it can take
 *  keystrokes. Real UI latency on the other machine, not network latency — so it lives
 *  here rather than in a server-side delay.
 *  ponytail: a fixed guess, not a wait-for-window-open signal; bump if a slow PC still
 *  drops the first few characters. */
private const val OPEN_DELAY_MS = 320L
private const val ENTER_DELAY_MS = 90L

/**
 * The quick-access page — search, launched apps, shortcuts and power/media, in the same
 * instrument language as the Stats dashboard (docs/design-system.md §7's StatsScreen
 * entry): chamfered panels, monospace labels, the cyan `live` accent. Ships with ten
 * defaults (see `defaultDeckItems`) and is fully rebuildable from the "Customize" toggle
 * — add, edit, reorder and remove tiles, all kept in [items].
 *
 * Nothing here is a new server capability. Every tile resolves to the same `/control`
 * messages the trackpad and keyboard already send — a launch is Win+R, a search is the
 * Start menu or a browser search URL, both typed and entered the way a keyboard would.
 */
@Composable
fun DeckScreen(
    items: List<DeckItem>,
    onItemsChange: (List<DeckItem>) -> Unit,
    send: (JSONObject) -> Unit,
    activeWindow: ActiveWindow?,
    /** Starts and stops the PC watching its own foreground app — subscribe-while-open,
     *  the same shape as the Stats screen's own watch callback. */
    onWatchActiveWindow: (Boolean) -> Unit,
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<DeckItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var queryPrompt by remember { mutableStateOf<DeckItem?>(null) }
    var confirmPower by remember { mutableStateOf<DeckItem?>(null) }

    DisposableEffect(Unit) {
        onWatchActiveWindow(true)
        onDispose { onWatchActiveWindow(false) }
    }

    fun openEditor(item: DeckItem?) {
        editorTarget = item
        showEditor = true
    }

    Column(modifier = Modifier.fillMaxSize().hudCanvas()) {
        DeckHeader(editing = editing, onToggleEdit = { editing = !editing })
        ContextPanel(activeWindow = activeWindow, send = send)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 92.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                DeckTile(
                    item = item,
                    editing = editing,
                    canMoveEarlier = index > 0,
                    canMoveLater = index < items.lastIndex,
                    onMoveEarlier = { onItemsChange(items.moved(index, index - 1)) },
                    onMoveLater = { onItemsChange(items.moved(index, index + 1)) },
                    onDelete = { onItemsChange(items.filterNot { it.id == item.id }) },
                    onEdit = { openEditor(item) },
                    onTap = {
                        when {
                            item.type == DeckActionType.WEB_SEARCH.name || item.type == DeckActionType.PC_SEARCH.name -> {
                                haptics.tick()
                                queryPrompt = item
                            }
                            item.type == DeckActionType.POWER.name && item.payload in DESTRUCTIVE_POWER -> {
                                haptics.tick()
                                confirmPower = item
                            }
                            else -> {
                                haptics.tick()
                                scope.launch { runDeckItem(item, null, send) }
                            }
                        }
                    },
                )
            }
            item(key = "__add__") { AddTile(onClick = { openEditor(null) }) }
        }
    }

    queryPrompt?.let { item ->
        SearchPromptDialog(
            item = item,
            onDismiss = { queryPrompt = null },
            onGo = { query ->
                scope.launch { runDeckItem(item, query, send) }
                queryPrompt = null
            },
        )
    }

    confirmPower?.let { item ->
        val preset = POWER_PRESETS.firstOrNull { it.mode == item.payload }
        val label = preset?.label ?: item.label
        AlertDialog(
            onDismissRequest = { confirmPower = null },
            icon = {
                Icon(DeckIcon.of(item.icon).icon, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("$label the PC?") },
            text = { Text("Anything unsaved on the PC will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { runDeckItem(item, null, send) }
                    confirmPower = null
                }) { Text(label, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPower = null }) { Text("Cancel") } },
        )
    }

    if (showEditor) {
        DeckEditorSheet(
            existing = editorTarget,
            onDismiss = { showEditor = false },
            onSave = { item ->
                onItemsChange(
                    if (items.any { it.id == item.id }) {
                        items.map { if (it.id == item.id) item else it }
                    } else {
                        items + item
                    },
                )
                showEditor = false
            },
            onDelete = { id ->
                onItemsChange(items.filterNot { it.id == id })
                showEditor = false
            },
        )
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    val mutable = toMutableList()
    mutable.add(to, mutable.removeAt(from))
    return mutable
}

/** What a tile actually sends, given the query the user typed (or null to fall back to
 *  the tile's own default). Delays are real UI latency on the PC's side — see
 *  [OPEN_DELAY_MS] — not network latency, so they live on the client rather than the
 *  server. */
private suspend fun runDeckItem(item: DeckItem, query: String?, send: (JSONObject) -> Unit) {
    when (item.type) {
        DeckActionType.RUN.name -> runViaWinR(item.payload, send)

        DeckActionType.WEB_SEARCH.name -> {
            val q = (query ?: item.payload).trim()
            if (q.isEmpty()) return
            val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
            runViaWinR(url, send)
        }

        DeckActionType.PC_SEARCH.name -> {
            val q = (query ?: item.payload).trim()
            if (q.isEmpty()) return
            send(Protocol.tap("win"))
            delay(OPEN_DELAY_MS)
            send(Protocol.text(q))
            delay(ENTER_DELAY_MS)
            send(Protocol.tap("enter"))
        }

        DeckActionType.SHORTCUT.name ->
            if (item.keys.isNotEmpty()) send(Protocol.combo(*item.keys.toTypedArray()))

        DeckActionType.POWER.name ->
            if (item.payload.isNotBlank()) send(Protocol.power(item.payload))

        DeckActionType.MEDIA.name ->
            if (item.payload.isNotBlank()) send(Protocol.media(item.payload))

        else -> Unit
    }
}

/** Win+R, typed into, entered — the one flow both "launch this command" and "open this
 *  URL" reduce to: the Run box resolves a bare command (`notepad`, `explorer`) exactly
 *  as typing it at a prompt would, and hands anything else to whatever's registered for
 *  it (a `.exe` path, a `https://` link) the same way Explorer would. */
private suspend fun runViaWinR(command: String, send: (JSONObject) -> Unit) {
    if (command.isBlank()) return
    send(Protocol.combo("win", "r"))
    delay(OPEN_DELAY_MS)
    send(Protocol.text(command))
    delay(ENTER_DELAY_MS)
    send(Protocol.tap("enter"))
}

@Composable
private fun DeckHeader(editing: Boolean, onToggleEdit: () -> Unit) {
    val hud = PortalRemoteTheme.hud
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DECK", style = HudType.Label, color = hud.textDim, modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleEdit) {
            Icon(
                if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                contentDescription = null,
                tint = hud.live,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (editing) "Done" else "Customize", color = hud.live, style = HudType.Label)
        }
    }
}

/**
 * The touch-bar row: whatever app has focus on the PC, and the handful of shortcuts that
 * make sense for it — [contextActionsFor]. Sits above the grid, always visible, and
 * updates itself as the user switches windows over there; unlike a Deck tile this isn't
 * saved, since it's a reflection of the PC's state rather than something the user set up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextPanel(activeWindow: ActiveWindow?, send: (JSONObject) -> Unit) {
    val hud = PortalRemoteTheme.hud
    val haptics = LocalHaptics.current
    val process = activeWindow?.process
    val actions = if (process.isNullOrBlank()) FALLBACK_CONTEXT_ACTIONS else contextActionsFor(process)

    HudPanel(
        title = if (process.isNullOrBlank()) "CONTEXT" else process.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                AssistChip(
                    onClick = {
                        haptics.tick()
                        send(Protocol.combo(*action.keys.toTypedArray()))
                    },
                    label = { Text(action.label) },
                )
            }
        }
    }
}

/** One tile: chamfered face, the live accent on its glyph, its label underneath. In edit
 *  mode a tap opens the editor instead of running the tile, a remove button appears at
 *  its corner, and two arrows step it earlier/later in the grid — reading order, since a
 *  square grid has no natural up/down. */
@Composable
private fun DeckTile(
    item: DeckItem,
    editing: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onTap: () -> Unit,
) {
    val hud = PortalRemoteTheme.hud
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(HudPanelShape)
            .background(hud.panel)
            .border(1.dp, hud.edge, HudPanelShape)
            .clickable(onClick = if (editing) onEdit else onTap),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                DeckIcon.of(item.icon).icon,
                contentDescription = null,
                tint = hud.live,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.label.uppercase(),
                style = HudType.Label,
                color = hud.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        if (editing) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd).size(26.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${item.label}",
                    tint = hud.alarm,
                    modifier = Modifier.size(15.dp),
                )
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)) {
                IconButton(onClick = onMoveEarlier, enabled = canMoveEarlier, modifier = Modifier.size(22.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Move ${item.label} earlier",
                        tint = hud.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onMoveLater, enabled = canMoveLater, modifier = Modifier.size(22.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Move ${item.label} later",
                        tint = hud.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val hud = PortalRemoteTheme.hud
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(HudPanelShape)
            .border(1.dp, hud.edge, HudPanelShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add tile", tint = hud.textDim, modifier = Modifier.size(28.dp))
    }
}

/** Search tiles ask each tap rather than firing blind — "quick search" means typing
 *  something new most of the time, and a saved default (set in the editor) pre-fills it
 *  for the times it doesn't. */
@Composable
private fun SearchPromptDialog(item: DeckItem, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.payload) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(DeckIcon.of(item.icon).icon, contentDescription = null) },
        title = { Text(item.label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Search…") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onGo(text) }, enabled = text.isNotBlank()) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add or edit one tile: a label, a type (which decides the field below it), an icon, and
 * Save/Delete. [existing] null means "new tile, blank fields"; non-null pre-fills from it
 * and offers Delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeckEditorSheet(
    existing: DeckItem?,
    onDismiss: () -> Unit,
    onSave: (DeckItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var type by remember {
        mutableStateOf(DeckActionType.entries.firstOrNull { it.name == existing?.type } ?: DeckActionType.SHORTCUT)
    }
    var icon by remember { mutableStateOf(existing?.icon?.let { DeckIcon.of(it) } ?: DeckIcon.APPS) }
    var command by remember { mutableStateOf(if (existing?.type == DeckActionType.RUN.name) existing.payload else "") }
    var query by remember {
        val isSearch = existing?.type == DeckActionType.WEB_SEARCH.name || existing?.type == DeckActionType.PC_SEARCH.name
        mutableStateOf(if (isSearch) existing?.payload ?: "" else "")
    }
    var keys by remember { mutableStateOf(existing?.keys ?: emptyList()) }
    var powerMode by remember { mutableStateOf(if (existing?.type == DeckActionType.POWER.name) existing.payload else "lock") }
    var mediaAction by remember {
        mutableStateOf(if (existing?.type == DeckActionType.MEDIA.name) existing.payload else "play_pause")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (existing == null) "Add tile" else "Edit tile", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Type", style = HudType.Label, color = PortalRemoteTheme.hud.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckActionType.entries.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = { type = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }

            Text("Icon", style = HudType.Label, color = PortalRemoteTheme.hud.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckIcon.entries.forEach { candidate -> IconPickerButton(candidate, icon == candidate) { icon = candidate } }
            }

            when (type) {
                DeckActionType.RUN -> OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command or path") },
                    placeholder = { Text("notepad, calc, explorer, C:\\…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DeckActionType.WEB_SEARCH, DeckActionType.PC_SEARCH -> OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Default query (optional)") },
                    placeholder = { Text("Leave blank to ask each time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DeckActionType.SHORTCUT -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SHORTCUT_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = keys == preset.keys,
                            onClick = {
                                keys = preset.keys
                                if (label.isBlank()) label = preset.label
                                icon = preset.icon
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }

                DeckActionType.POWER -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    POWER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = powerMode == preset.mode,
                            onClick = {
                                powerMode = preset.mode
                                if (label.isBlank()) label = preset.label
                                icon = preset.icon
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }

                DeckActionType.MEDIA -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MEDIA_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = mediaAction == preset.action,
                            onClick = {
                                mediaAction = preset.action
                                if (label.isBlank()) label = preset.label
                                icon = preset.icon
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }

            val valid = label.isNotBlank() && when (type) {
                DeckActionType.RUN -> command.isNotBlank()
                DeckActionType.SHORTCUT -> keys.isNotEmpty()
                DeckActionType.WEB_SEARCH, DeckActionType.PC_SEARCH, DeckActionType.POWER, DeckActionType.MEDIA -> true
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = { onDelete(existing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = valid,
                    onClick = {
                        val payload = when (type) {
                            DeckActionType.RUN -> command.trim()
                            DeckActionType.WEB_SEARCH, DeckActionType.PC_SEARCH -> query.trim()
                            DeckActionType.POWER -> powerMode
                            DeckActionType.MEDIA -> mediaAction
                            DeckActionType.SHORTCUT -> ""
                        }
                        onSave(
                            DeckItem(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                label = label.trim(),
                                icon = icon.name,
                                type = type.name,
                                payload = payload,
                                keys = if (type == DeckActionType.SHORTCUT) keys else emptyList(),
                            ),
                        )
                    },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun IconPickerButton(candidate: DeckIcon, selected: Boolean, onClick: () -> Unit) {
    val hud = PortalRemoteTheme.hud
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) hud.live.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) hud.live else hud.edge, CircleShape),
    ) {
        Icon(candidate.icon, contentDescription = null, tint = if (selected) hud.live else hud.textDim)
    }
}
