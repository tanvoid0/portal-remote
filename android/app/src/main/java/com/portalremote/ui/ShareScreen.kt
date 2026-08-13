package com.portalremote.ui

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.portalremote.MainActivity
import com.portalremote.R
import com.portalremote.data.SavedHost
import com.portalremote.net.CastUrl
import com.portalremote.net.FileApi
import com.portalremote.net.ShareEntry
import com.portalremote.net.ShareKind
import com.portalremote.ui.theme.LocalHaptics
import kotlinx.coroutines.launch

/**
 * Quick share, as a conversation between this phone and the PC.
 *
 * Two views over one history, because it answers two different questions:
 *
 * - **Chat** — what just happened. Bubbles sided by device (yours on the right, the
 *   PC's on the left), newest at the bottom, composer under them. A hand-off between
 *   two devices in front of you *is* a conversation, and a thread is the shape people
 *   already read fluently for "who sent what, in what order".
 * - **Library** — where did that thing go. The same entries as rows, with search, a
 *   filter per kind and a newest/oldest sort. A thread is the wrong tool for "that PDF
 *   from earlier": all it offers is scrolling.
 *
 * The system share sheet (see the ACTION_SEND filter in AndroidManifest.xml) is still
 * the primary way *in* — opening this app to share something would defeat the point.
 * The composer is for what the share sheet can't reach: the clipboard, a note typed
 * now, and a picture or file when you're already standing here.
 *
 * [bottomInset] is the height of the shell's frosted nav bar (see RemoteScreen). The
 * library's rows run under it and re-apply it as content padding; in the chat the
 * composer clears it outright, since a text field you can see through the glass and
 * can't reliably hit is worse than one that stops above it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ShareScreen(
    host: SavedHost,
    shares: List<ShareEntry>,
    onShareText: (String) -> Unit,
    onShareUri: (Uri) -> Unit,
    onRetry: (Long) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val fileApi = remember { FileApi() }
    var library by rememberSaveable { mutableStateOf(false) }

    // Asked for here rather than at launch: this is the only screen that posts a
    // notification, and a permission prompt in front of a screen that doesn't need
    // one reads as overreach (same reasoning as PairScreen's camera prompt).
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }
    LaunchedEffect(Unit) {
        if (notifications?.status?.isGranted == false) notifications.launchPermissionRequest()
    }

    // One tap behaviour for both views: a queued share retries on the next reconnect by
    // itself, and the tap is for when you can see the PC is back and don't want to wait
    // for the socket to notice.
    val open: (ShareEntry) -> Unit = { entry ->
        if (entry.isQueued) {
            onRetry(entry.id)
        } else {
            openShare(context, fileApi, host, entry)?.let { message ->
                scope.launch { snackbarHost.showSnackbar(message) }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHost, modifier = Modifier.padding(bottom = bottomInset))
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ViewSwitch(library = library, onChange = { library = it })
            HorizontalDivider()

            if (library) {
                ShareLibrary(
                    shares = shares,
                    onOpen = open,
                    bottomInset = bottomInset,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ChatThread(shares = shares, onOpen = open, modifier = Modifier.weight(1f))
                Composer(onShareText = onShareText, onShareUri = onShareUri, bottomInset = bottomInset)
            }
        }
    }
}

/** Chat or library. Chips rather than a `TabRow`: this is chrome over one feature —
 *  the same job the mirror's chips do — not a second level of navigation under the
 *  shell's own tabs. */
@Composable
private fun ViewSwitch(library: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !library,
            onClick = { onChange(false) },
            leadingIcon = { ChipIcon(Icons.Filled.Forum) },
            label = { Text("Chat") },
        )
        FilterChip(
            selected = library,
            onClick = { onChange(true) },
            leadingIcon = { ChipIcon(Icons.Filled.GridView) },
            label = { Text("Library") },
        )
    }
}

// --- Chat -----------------------------------------------------------------------

/**
 * The thread. `reverseLayout` rather than a re-sorted copy: the model is newest-first
 * already, and reversing the *layout* puts the newest at the bottom, opens the view
 * there, and makes an arriving share push in from the bottom edge — three things for
 * free, none of them true of a list merely sorted the other way.
 */
@Composable
private fun ChatThread(
    shares: List<ShareEntry>,
    onOpen: (ShareEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shares.isEmpty()) {
        Box(modifier) {
            EmptyState(
                icon = Icons.Filled.SwapVert,
                title = "Nothing shared yet",
                detail = "Send something below, or share a link or image to Portal Remote " +
                    "from any app. From the PC, press Ctrl+Alt+V.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(shares, key = { it.id }) { entry ->
            ShareBubble(entry = entry, onClick = { onOpen(entry) })
        }
    }
}

/**
 * One message. Side and colour carry the direction, so no bubble has to spell out who
 * sent it — the job the icon tint does in the library, done here by a property the eye
 * reads without stopping.
 */
@Composable
private fun ShareBubble(entry: ShareEntry, onClick: () -> Unit) {
    val mine = !entry.incoming
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            // The square corner points at its own side of the thread: the classic chat
            // tail, minus the tail. Cut rather than rounded, like every other surface in
            // the app (§5) — which makes the idea read *better* here, because a 45°
            // chamfer on three corners and a hard right angle on the fourth is a more
            // obvious pointer than a radius change was.
            shape = CutCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomEnd = if (mine) 0.dp else 12.dp,
                bottomStart = if (mine) 12.dp else 0.dp,
            ),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp).clickable(onClick = onClick),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                val text = entry.text
                if (text == null || entry.kind == ShareKind.FILE || entry.kind == ShareKind.IMAGE) {
                    // A file is an attachment, not a sentence: icon plus name, and the
                    // tap on the bubble downloads it.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            iconForShare(entry.kind),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Text(
                        text.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        // Long enough to read a paragraph in place, short enough that
                        // one pasted article can't own the screen.
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Not error-red: a share that hasn't gone yet isn't lost, it's queued,
                // and the app sends it on the next reconnect without being asked.
                entry.status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The send end of the thread: clipboard suggestion, attach menu, field, send.
 *
 * One field for everything typed — [ShareKind.forText] decides link vs note on its
 * own, and on both ends of the wire, so asking the user to declare it would be asking
 * for something already known.
 */
@Composable
private fun Composer(onShareText: (String) -> Unit, onShareUri: (Uri) -> Unit, bottomInset: Dp) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var clip by remember { mutableStateOf<Clip?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var attaching by remember { mutableStateOf(false) }

    // Read on entry and on each resume, never continuously: Android 12+ posts a
    // "Portal Remote pasted from your clipboard" toast per read, and a suggestion that
    // polled would be a stream of them. Once per look at the screen is all it needs —
    // a copy made in another app lands on the way back here.
    DisposableEffect(lifecycleOwner) {
        val refresh = { clip = readClipboard(context) }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The system photo picker, not a media permission: it hands back one uri and
    // grants nothing else, so there is no runtime prompt to answer.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onShareUri) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onShareUri)
    }

    val send = {
        if (draft.isNotBlank()) {
            haptics.tap()
            onShareText(draft)
            draft = ""
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        // The inset is applied inside the surface, not around it: the composer's own
        // background runs to the bottom of the screen and behind the frosted bar, while
        // its controls stop above it.
        Column(modifier = Modifier.padding(bottom = bottomInset)) {
            // Hidden once there's a draft: at that point the clipboard isn't what the
            // user is about to send, and a second send button beside the first is a
            // question nobody asked.
            clip?.takeIf { draft.isBlank() }?.let { pending ->
                ClipboardSuggestion(
                    clip = pending,
                    onSend = {
                        haptics.tap()
                        pending.uri?.let(onShareUri) ?: pending.text?.let(onShareText)
                    },
                )
                HorizontalDivider()
            }

            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    IconButton(onClick = { attaching = true }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                    }
                    DropdownMenu(expanded = attaching, onDismissRequest = { attaching = false }) {
                        DropdownMenuItem(
                            text = { Text("Image") },
                            leadingIcon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                            onClick = {
                                attaching = false
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                            },
                            onClick = { attaching = false; pickFile.launch("*/*") },
                        )
                    }
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Send to your PC") },
                    // Grows to a few lines, then scrolls: a note is usually one line,
                    // and a pasted paragraph shouldn't eat the thread above it.
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )

                FilledIconButton(
                    onClick = send,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Send what's on the clipboard, showing it first. The preview is the point: the
 * clipboard is invisible state, and a button that sends it blind is one you have to
 * check afterwards — in the thread, on the PC — to know what it did.
 */
@Composable
private fun ClipboardSuggestion(clip: Clip, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSend)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumb = clip.thumbnail
        if (thumb != null) {
            // A copied image says nothing as a content:// string, which is what its
            // "preview" would otherwise be. Same slot as the kind icon, so the row keeps
            // one shape whatever is on the clipboard.
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small),
            )
        } else {
            Icon(
                // The kind icon, so the row says "link" before the PC opens a browser.
                clip.text?.let { iconForShare(ShareKind.forText(it)) } ?: Icons.Filled.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Send clipboard", style = MaterialTheme.typography.labelLarge)
            Text(
                clip.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

// --- Library --------------------------------------------------------------------

/** The kinds the filter offers, in the order they appear. `null` is "everything". */
private val KIND_FILTERS = listOf(
    null to "All",
    ShareKind.LINK to "Links",
    ShareKind.IMAGE to "Images",
    ShareKind.FILE to "Files",
    ShareKind.TEXT to "Notes",
)

/**
 * The same history as rows, searchable and filterable — the view for finding a thing
 * again rather than for seeing what just arrived.
 *
 * Filtering is a plain list operation over what's already in memory: the model caps the
 * history at a couple of hundred entries, so anything cleverer would be an index over a
 * list that fits in a screenful of RAM.
 */
@Composable
private fun ShareLibrary(
    shares: List<ShareEntry>,
    onOpen: (ShareEntry) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }

    val shown = remember(shares, query, kind, newestFirst) {
        shares
            .filter { entry ->
                (kind == null || entry.kind == kind) && (query.isBlank() || entry.matches(query))
            }
            // Ids are handed out in arrival order, so they *are* the timeline — there's
            // no timestamp on the model to sort by, and none needed.
            .sortedByDescending { if (newestFirst) it.id else -it.id }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search shares") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KIND_FILTERS.forEach { (value, label) ->
                FilterChip(
                    selected = kind == value,
                    onClick = { kind = value },
                    // The same glyph the rows below carry, so the filter and what it
                    // filters to are one thing rather than two vocabularies.
                    leadingIcon = {
                        ChipIcon(value?.let { iconForShare(it) } ?: Icons.Filled.SelectAll)
                    },
                    label = { Text(label) },
                )
            }
            // The sort is one chip that flips, not a menu: there are exactly two orders,
            // and a menu to pick between two things is a menu too many.
            FilterChip(
                selected = false,
                onClick = { newestFirst = !newestFirst },
                label = { Text(if (newestFirst) "Newest" else "Oldest") },
                leadingIcon = {
                    ChipIcon(
                        if (newestFirst) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    )
                },
            )
        }

        if (shown.isEmpty()) {
            EmptyState(
                icon = if (shares.isEmpty()) Icons.Filled.SwapVert else Icons.Filled.SearchOff,
                title = if (shares.isEmpty()) "Nothing shared yet" else "Nothing matches",
                detail = if (shares.isEmpty()) {
                    "Anything you and the PC hand each other shows up here."
                } else {
                    "Try a different word, or another kind."
                },
            )
        } else {
            // Content padding, not a Modifier.padding: the rows have to scroll *under*
            // the frosted bar — that translucency has to have something moving behind it
            // — while the last one still ends above it.
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset)) {
                items(shown, key = { it.id }) { entry ->
                    ShareRow(entry = entry, onClick = { onOpen(entry) })
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Free-text match over everything a row shows: the payload, the file name, the sender. */
private fun ShareEntry.matches(query: String): Boolean {
    val needle = query.trim()
    return text?.contains(needle, ignoreCase = true) == true ||
        fileName?.contains(needle, ignoreCase = true) == true ||
        from.contains(needle, ignoreCase = true)
}

/** Standard Material3 list item spec — see docs/design-system.md §7. */
@Composable
private fun ShareRow(entry: ShareEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                entry.preview,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (entry.incoming) "from ${entry.from}" else "sent to your PC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // Not error-red: a share that hasn't gone yet isn't lost, it's
                        // queued, and the app sends it on the next reconnect without
                        // being asked. Colouring it as a failure would say otherwise.
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                iconForShare(entry.kind),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (entry.incoming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun iconForShare(kind: String): ImageVector = when (kind) {
    ShareKind.LINK -> Icons.Filled.Link
    ShareKind.IMAGE -> Icons.Filled.Photo
    ShareKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    else -> Icons.AutoMirrored.Filled.Article
}

/**
 * What tapping a share does, per kind: open a link, download a file the PC sent, and
 * for anything else put it back on the clipboard — the text was copied when it
 * arrived, but by the time you scroll back to an older entry it no longer is.
 * Returns a message to show, or null if the action speaks for itself.
 */
private fun openShare(
    context: Context,
    fileApi: FileApi,
    host: SavedHost,
    entry: ShareEntry,
): String? {
    val path = entry.path
    if (path != null) {
        val request = DownloadManager.Request(fileApi.downloadUrl(host, path).toUri()).apply {
            addRequestHeader("Authorization", "Bearer ${host.token}")
            setTitle(entry.fileName ?: "Shared file")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, entry.fileName ?: "shared.bin")
            setAllowedOverMetered(true)
        }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, "Downloading ${entry.fileName}…", Toast.LENGTH_SHORT).show()
        return null
    }

    val text = entry.text ?: return null
    if (entry.kind == ShareKind.LINK) {
        // Both `kind` and `text` arrive from the PC (ShareEntry.fromPush), so the
        // http(s) check is re-done here rather than taken on the sender's word: this
        // hands a URL straight to the system, and `intent://` or a custom scheme is a
        // launch into another app, not a page. Same rule the PC applies in the other
        // direction before ShellExecute (docs/security.md); CastUrl is where it already
        // lives on this side.
        val target = CastUrl.normalize(text) ?: return "That isn't a link this app will open."
        // ACTION_VIEW with no chooser: the user's default browser is the right answer,
        // and this is a link they just sent themselves from the other device.
        val browse = Intent(Intent.ACTION_VIEW, target.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(browse) }
            .fold(onSuccess = { null }, onFailure = { "No app can open that link." })
    }

    copyToClipboard(context, text)
    return "Copied to clipboard"
}

// --- Clipboard and notifications ------------------------------------------------
// Live here rather than in net/ because they're this feature's *output* to the user,
// the same category as the screen above.

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    // Android 13+ shows its own "copied" confirmation, so nothing more to say here.
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Portal Remote", text)) }
}

/**
 * What's on the clipboard, as far as the composer cares: text, or an image with its
 * thumbnail already decoded. Exactly one of [text] and [uri] is set.
 */
private data class Clip(
    val text: String? = null,
    val uri: Uri? = null,
    val thumbnail: ImageBitmap? = null,
    val label: String? = null,
) {
    /** The line under "Send clipboard" — for an image the file name, since the
     *  picture itself is already the preview beside it. */
    val preview: String get() = text?.trim() ?: label ?: "Image"
}

private fun readClipboard(context: Context): Clip? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)

    // A copied image arrives as a content uri; coerceToText would turn it into the
    // uri string, which is neither useful to look at nor useful on the PC. The read
    // grant that comes with the clip is what makes the upload work, and it lasts as
    // long as this stays the primary clip — long enough for a tap on the suggestion.
    val uri = item.uri
    if (uri != null && context.contentResolver.getType(uri)?.startsWith("image/") == true) {
        return Clip(
            uri = uri,
            thumbnail = thumbnailOf(context, uri),
            label = displayNameOf(context, uri),
        )
    }

    return item.coerceToText(context).toString().takeIf { it.isNotBlank() }?.let { Clip(text = it) }
}

/**
 * Decode [uri] small enough for a 36dp preview. Two passes rather than a fixed
 * `inSampleSize`: this runs on the main thread when the screen resumes, and the
 * clipboard holds anything from a 12MP camera JPEG to a 32px favicon.
 */
private fun thumbnailOf(context: Context, uri: Uri, target: Int = 128): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val options = BitmapFactory.Options().apply {
        inSampleSize = (minOf(bounds.outWidth, bounds.outHeight) / target).coerceAtLeast(1)
    }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()?.asImageBitmap()

private const val SHARE_CHANNEL_ID = "shares"

/**
 * Post a heads-up notification for something the PC just sent. Only fires while the
 * app is running — receiving with the app closed would need a foreground service and
 * its permanent notification, which is a worse trade than it sounds; see
 * docs/phase5-share.md.
 */
internal fun notifyShare(context: Context, entry: ShareEntry) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    manager.createNotificationChannel(
        NotificationChannel(
            SHARE_CHANNEL_ID,
            "Shares",
            // HIGH so it arrives as a heads-up banner: the whole value is noticing it
            // without going looking, and these are rare and always user-initiated.
            NotificationManager.IMPORTANCE_HIGH,
        )
    )

    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val title = when (entry.kind) {
        ShareKind.LINK -> "${entry.from} shared a link"
        ShareKind.IMAGE -> "${entry.from} shared an image"
        ShareKind.FILE -> "${entry.from} shared a file"
        else -> "${entry.from} shared a note"
    }

    val notification = NotificationCompat.Builder(context, SHARE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_portal_mark)
        .setContentTitle(title)
        .setContentText(entry.preview)
        .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text ?: entry.preview))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()

    // The runtime grant is asked for on ShareScreen; if it was refused,
    // areNotificationsEnabled() above already sent us home.
    runCatching { manager.notify(entry.id.toInt(), notification) }
}

/** The human-readable name behind a content:// uri, for naming an upload. */
internal fun displayNameOf(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } finally {
        cursor?.close()
    }
}
