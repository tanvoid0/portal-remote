package com.portalremote.ui

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 */
@Composable
fun FilesScreen(host: SavedHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileApi = remember { FileApi() }
    val snackbarHost = remember { SnackbarHostState() }

    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                entries = fileApi.list(host, currentPath)
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
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
            try {
                val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "upload.bin"
                val type = context.contentResolver.getType(uri)
                fileApi.upload(context, host, currentPath, name, type, uri)
                snackbarHost.showSnackbar("Uploaded $name")
                reload()
            } catch (e: Exception) {
                snackbarHost.showSnackbar("Upload failed: ${e.message}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFile.launch("*/*") },
                icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                text = { Text("Upload") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(path = currentPath, onNavigate = { currentPath = it })
            HorizontalDivider()

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Couldn't load this folder: $error", color = MaterialTheme.colorScheme.error)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
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
                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
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

private fun queryDisplayName(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else null
    } finally {
        cursor?.close()
    }
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
