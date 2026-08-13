package com.portalremote.ui

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.portalremote.data.SavedHost
import com.portalremote.net.FileApi
import com.portalremote.net.RemoteFileEntry
import kotlinx.coroutines.launch

/**
 * Breadcrumb-navigable browser over the server's shared folder: tap a folder to
 * enter it, tap a file to download it via the system Download Manager (which
 * gives us a real progress notification for free), upload via the system file
 * picker.
 *
 * [bottomInset] is the height of the shell's nav bar, which this screen's list runs
 * *under* (it's the frosted one — see RemoteScreen). The list re-applies it as content
 * padding so the last row still clears the bar, and the FAB and snackbar sit above it.
 */
@OptIn(ExperimentalMaterial3Api::class) // PullToRefreshBox
@Composable
fun FilesScreen(host: SavedHost, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileApi = remember { FileApi() }
    val snackbarHost = remember { SnackbarHostState() }

    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Name + fraction of the upload in flight, or null. Also the re-entrancy guard:
    // the FAB is disabled while it's set, since the picker will happily hand back the
    // same file twice and the second copy lands beside the first on the PC.
    var upload by remember { mutableStateOf<Pair<String, Float>?>(null) }

    /**
     * [showSpinner] false is the pull-to-refresh path: the gesture has its own
     * indicator, and swapping the list for a centered spinner under the user's finger
     * throws away the rows they were looking at to say something already being said.
     */
    fun reload(showSpinner: Boolean = true) {
        scope.launch {
            if (showSpinner) loading = true else refreshing = true
            error = null
            try {
                entries = fileApi.list(host, currentPath)
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(currentPath) { reload() }

    BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: "upload.bin"
            try {
                val type = context.contentResolver.getType(uri)
                upload = name to 0f
                fileApi.upload(context, host, currentPath, name, type, uri) { sent, total ->
                    // Reported from the IO thread. Compose state is snapshot-safe to
                    // write from anywhere, and this only ever sets a fresh value.
                    upload = name to if (total > 0) (sent.toFloat() / total) else 0f
                }
                snackbarHost.showSnackbar("Uploaded $name")
                reload()
            } catch (e: Exception) {
                snackbarHost.showSnackbar("Upload failed: ${e.message}")
            } finally {
                upload = null
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHost, modifier = Modifier.padding(bottom = bottomInset))
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // Disabled during an upload rather than queueing a second one: the
                // picker will hand back the same file again without complaint, and two
                // copies on the PC is a worse outcome than waiting.
                onClick = { if (upload == null) pickFile.launch("*/*") },
                icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                text = { Text(if (upload == null) "Upload" else "Uploading…") },
                modifier = Modifier.padding(bottom = bottomInset),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(path = currentPath, onNavigate = { currentPath = it })
            HorizontalDivider()

            upload?.let { (name, fraction) -> UploadProgress(name, fraction) }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // The one state the user can't leave by scrolling or tapping a row, so
                // it carries its own way out. Nothing else on this screen retries a
                // failed listing — at the root there isn't even a folder to step into
                // and back to.
                error != null -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = "Couldn't load this folder",
                    detail = error ?: "",
                    tint = MaterialTheme.colorScheme.error,
                    action = { Button(onClick = { reload() }) { Text("Try again") } },
                )
                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    // The PC's folder changes without the phone hearing about it —
                    // something dropped in from Share, or saved there by the PC itself.
                    // The list is a snapshot from whenever the folder was opened, and
                    // this is the only thing that says otherwise.
                    onRefresh = { reload(showSpinner = false) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (entries.isEmpty()) {
                        // Inside the box, not beside it: an empty folder is the most
                        // likely one to want refreshing, and a state that can't be
                        // pulled is a state you have to leave to re-check.
                        EmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = "Nothing here yet",
                            detail = "Tap Upload to send a file to your PC's shared folder, " +
                                "or pull down to check again.",
                            scrollable = true,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = bottomInset),
                        ) {
                            items(entries, key = { it.name }) { entry ->
                                FileRow(
                                    entry = entry,
                                    onClick = {
                                        if (entry.isDir) {
                                            currentPath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
                                        } else {
                                            startDownload(context, fileApi, host, currentPath, entry)
                                        }
                                    },
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
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = if (path.isEmpty()) emptyList() else path.split('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Home",
            modifier = Modifier.clickable { onNavigate("") },
            color = if (segments.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
        segments.forEachIndexed { index, name ->
            Text(" / ")
            val isLast = index == segments.lastIndex
            Text(
                name,
                modifier = Modifier.clickable { onNavigate(segments.subList(0, index + 1).joinToString("/")) },
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Icon + one line of what to do next, for the two states where the list has nothing
 * to show. A bare sentence centered in an otherwise blank screen reads as a glitch;
 * the icon makes it read as a state.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** The way out, for a state the user can't leave by scrolling or tapping a row. */
    action: (@Composable () -> Unit)? = null,
    /** Makes the state itself scrollable so a `PullToRefreshBox` around it still has a
     *  gesture to read — the pull needs a scrollable child, and an empty folder is
     *  exactly the one you want to re-check. */
    scrollable: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            action?.let {
                Box(modifier = Modifier.padding(top = 16.dp)) { it() }
            }
        }
    }
}

/** A determinate bar, never an indeterminate one: this is a known number of bytes
 *  over a LAN, and a shimmer would misreport it as unknown-duration — see
 *  docs/design-system.md §7. Falls back to indeterminate only when the content
 *  provider refuses to say how big the file is. */
@Composable
private fun UploadProgress(name: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Uploading $name",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (fraction > 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * File-type icon from the extension. Worth the lookup table: this list is the one
 * place in the app showing content the user has to recognize at a glance, and a
 * column of identical document glyphs makes every row look the same.
 */
private fun iconForFile(name: String): ImageVector =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> Icons.Filled.Image
        "mp4", "mkv", "mov", "avi", "webm", "m4v" -> Icons.Filled.Movie
        "mp3", "wav", "flac", "m4a", "ogg", "aac" -> Icons.Filled.AudioFile
        "pdf" -> Icons.Filled.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz", "xz" -> Icons.Filled.FolderZip
        "exe", "msi", "apk", "iso" -> Icons.Filled.Terminal
        "txt", "md", "log", "csv", "json", "xml", "yml", "yaml" -> Icons.AutoMirrored.Filled.Article
        else -> Icons.Filled.Description
    }

/** Standard Material3 list item spec — see docs/design-system.md §7. */
@Composable
private fun FileRow(entry: RemoteFileEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (entry.isDir) null else {
            {
                Text(
                    formatBytes(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else iconForFile(entry.name),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

private fun startDownload(
    context: Context,
    fileApi: FileApi,
    host: SavedHost,
    dirPath: String,
    entry: RemoteFileEntry,
) {
    val remotePath = if (dirPath.isEmpty()) entry.name else "$dirPath/${entry.name}"
    val url = fileApi.downloadUrl(host, remotePath).toUri()

    val request = DownloadManager.Request(url).apply {
        addRequestHeader("Authorization", "Bearer ${host.token}")
        setTitle(entry.name)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, entry.name)
        setAllowedOverMetered(true)
    }

    val manager = context.getSystemService(DownloadManager::class.java)
    manager.enqueue(request)
    Toast.makeText(context, "Downloading ${entry.name}…", Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
