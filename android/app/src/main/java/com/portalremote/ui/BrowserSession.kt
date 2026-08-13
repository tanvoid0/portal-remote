package com.portalremote.ui

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.portalremote.net.FoundMedia

/**
 * One browser tab. The [WebView] is attached lazily the first time the tab is shown,
 * and kept afterwards — a tab that reloads every time you switch back to it is not a
 * tab, it's a bookmark.
 */
class BrowserTab(
    val id: Long,
    val incognito: Boolean,
    initialUrl: String? = null,
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf<String?>(null)
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    /** Set when the page's only video source is a blob, so the sheet can explain. */
    var mediaHidden by mutableStateOf(false)

    val found = mutableStateListOf<FoundMedia>()

    /** Masters already sent off to be read for variants — players re-request them. */
    val expandedHls = mutableSetOf<String>()

    /** Held so the tab survives switching away and back. Destroyed when the tab closes. */
    var webView: WebView? = null

    val label: String get() = title?.takeIf { it.isNotBlank() } ?: url ?: "New tab"

    fun resetPageState() {
        found.clear()
        expandedHls.clear()
        mediaHidden = false
        blocked = 0
    }
}

/**
 * The set of open tabs, held above the app's own tab switcher so browsing survives a
 * trip to the trackpad and back.
 */
class BrowserSession {
    private var nextId = 1L

    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableStateOf(0L)

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }

    /** True once anything private is open — the UI goes dark-tinted while it is. */
    val hasIncognito: Boolean get() = tabs.any { it.incognito }

    fun open(url: String? = null, incognito: Boolean = false): BrowserTab {
        val tab = BrowserTab(nextId++, incognito, url)
        tabs.add(tab)
        activeId = tab.id
        return tab
    }

    fun ensureOne(): BrowserTab = active ?: tabs.firstOrNull()?.also { activeId = it.id } ?: open()

    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index == -1) return
        tab.webView?.destroy()
        tab.webView = null
        tabs.removeAt(index)
        if (activeId == tab.id) {
            // The neighbour on the left, as every browser does — closing the last tab
            // in a row shouldn't fling you to the far end of the strip.
            activeId = tabs.getOrNull(index - 1)?.id ?: tabs.firstOrNull()?.id ?: 0L
        }
    }

    fun closeIncognito() {
        tabs.filter { it.incognito }.forEach { close(it) }
    }

    fun destroyAll() {
        tabs.forEach {
            it.webView?.destroy()
            it.webView = null
        }
        tabs.clear()
        activeId = 0L
    }
}
