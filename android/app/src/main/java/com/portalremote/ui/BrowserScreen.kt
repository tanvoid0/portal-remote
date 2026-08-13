package com.portalremote.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.portalremote.data.BrowserSettings
import com.portalremote.data.BrowserStore
import com.portalremote.data.SearchEngine
import com.portalremote.net.AdBlock
import com.portalremote.net.FoundMedia
import com.portalremote.net.MediaSniffer
import com.portalremote.net.Omnibox
import java.io.ByteArrayInputStream
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Blank 200 rather than a 404: some scripts retry on error, and a retry loop is
 *  worse than the ad would have been. */
private fun blockedResponse() =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** Reads every `<video>` on the page, including ones with a plain `src=` attribute
 *  that never generate a request the interceptor can see. */
private const val FIND_VIDEOS_JS = """
    (function () {
      var out = [];
      var vids = document.querySelectorAll('video, video source');
      for (var i = 0; i < vids.length; i++) {
        var s = vids[i].currentSrc || vids[i].src;
        if (s) out.push(s);
      }
      return JSON.stringify(out);
    })()
"""

/**
 * The in-app browser — phase 4e of `docs/phase4-casting.md`, and the reason the rest
 * of phase 4 exists. It watches its own traffic for media URLs and hands them to the
 * PC, blocks ads and trackers at the request level, and refuses the popups that make
 * ad-funded video sites unusable on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    session: BrowserSession,
    onCast: (url: String, title: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserStore(context) }
    val settings by store.settings.collectAsState(initial = BrowserSettings())
    val bookmarks by store.bookmarks.collectAsState(initial = emptyList())
    val history by store.history.collectAsState(initial = emptyList())
    val adBlock = remember { AdBlock() }

    var showTabs by remember { mutableStateOf(false) }
    var showFound by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    // Set by onShowCustomView — a site going fullscreen for video hands us a view to
    // put over everything, and nothing else on screen should survive it.
    var fullscreenView by remember { mutableStateOf<View?>(null) }

    val tab = session.ensureOne()
    // A page paints white until its own background lands; on a dark app that's a flash
    // on every load. The WebView starts on the app's background colour instead.
    val pageBackground = MaterialTheme.colorScheme.background.toArgb()

    LaunchedEffect(Unit) { adBlock.load(context) }
    LaunchedEffect(settings.adBlockEnabled, settings.allowedHosts) {
        adBlock.enabled = settings.adBlockEnabled
        adBlock.allowedHosts = settings.allowedHosts
    }
    LaunchedEffect(tab.id, tab.url) { address = tab.url.orEmpty() }

    val pageHost = remember(tab.url) {
        tab.url?.let { AdBlock.hostOf(it) }?.let { AdBlock.siteKey(it) }
    }
    val siteAllowed = pageHost != null && pageHost in settings.allowedHosts
    val bookmarked = tab.url != null && bookmarks.any { it.url == tab.url }

    fun go(input: String) {
        val url = Omnibox.resolve(input, settings.searchEngine) ?: return
        tab.resetPageState()
        adBlock.resetCount()
        tab.webView?.loadUrl(url) ?: run { tab.url = url }
    }

    BackHandler(enabled = fullscreenView != null || tab.canGoBack) {
        when {
            fullscreenView != null -> tab.webView?.let { (it.webChromeClient)?.onHideCustomView() }
            else -> tab.webView?.goBack()
        }
    }

    // No imePadding() here: the shell hides the nav bar when the keyboard is up and
    // hands the IME inset to the tab content instead (RemoteScreen's contentWindowInsets),
    // so padding again would lift the page a second keyboard's worth.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserBar(
                address = address,
                incognito = tab.incognito,
                onAddressChange = { address = it },
                onGo = { go(address) },
                canGoBack = tab.canGoBack,
                canGoForward = tab.canGoForward,
                onBack = { tab.webView?.goBack() },
                onForward = { tab.webView?.goForward() },
                onReload = { tab.webView?.reload() },
                foundCount = tab.found.size,
                onShowFound = { showFound = true },
                tabCount = session.tabs.size,
                onShowTabs = { showTabs = true },
                bookmarked = bookmarked,
                onToggleBookmark = {
                    tab.url?.let { url -> scope.launch { store.toggleBookmark(url, tab.label) } }
                },
                shieldOn = settings.adBlockEnabled && !siteAllowed,
                onOpenMenu = { showSettings = true },
                onShowBookmarks = { showBookmarks = true },
                onShowHistory = { showHistory = true },
                onNewTab = { session.open() },
                onNewIncognitoTab = { session.open(incognito = true) },
                onToggleSiteBlocking = {
                    val host = pageHost ?: return@BrowserBar
                    scope.launch {
                        val next = if (siteAllowed) settings.allowedHosts - host
                        else settings.allowedHosts + host
                        store.saveSettings(settings.copy(allowedHosts = next))
                        tab.webView?.reload()
                    }
                },
            )

            if (tab.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // weight, not fillMaxSize: in a Column a fillMaxSize child claims the whole
            // height, and the WebView — a real Android View, drawn above Compose
            // content — then covers the address bar completely. It stays in the
            // accessibility tree, which is exactly how that hid until the pixels were
            // looked at.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Keyed on the tab so switching tabs swaps the whole WebView rather
                // than reusing one and reloading into it.
                key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            tab.webView ?: newWebView(
                                ctx = ctx,
                                tab = tab,
                                adBlock = adBlock,
                                background = pageBackground,
                                settingsProvider = { settings },
                                onTitle = { title ->
                                    tab.title = title
                                    val url = tab.url
                                    if (!tab.incognito && url != null && settings.saveHistory) {
                                        scope.launch {
                                            store.recordVisit(url, title.orEmpty(), nowMillis())
                                        }
                                    }
                                },
                                onFullscreen = { view -> fullscreenView = view },
                                openInNewTab = { url -> session.open(url, tab.incognito) },
                            ).also { created ->
                                tab.webView = created
                                tab.url?.let { created.loadUrl(it) }
                            }
                        },
                    )
                }

                if (tab.url == null) {
                    BrowserStartHint(
                        incognito = tab.incognito,
                        bookmarks = bookmarks,
                        onOpen = { url -> go(url) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }

        // Sits above the whole app chrome, which is what "fullscreen" has to mean.
        fullscreenView?.let { view ->
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        (view.parent as? ViewGroup)?.removeView(view)
                        addView(view)
                    }
                },
            )
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            TabSwitcher(
                session = session,
                onSelect = { session.activeId = it.id; showTabs = false },
                onClose = { session.close(it) },
                onNew = { incognito -> session.open(incognito = incognito); showTabs = false },
            )
        }
    }

    if (showFound) {
        ModalBottomSheet(onDismissRequest = { showFound = false }) {
            FoundMediaSheet(
                found = tab.found,
                mediaHidden = tab.mediaHidden,
                blocked = tab.blocked,
                onCast = { media ->
                    onCast(media.url, media.pageTitle ?: tab.title)
                    showFound = false
                },
            )
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            LinkListSheet(
                title = "Bookmarks",
                empty = "Star a page and it'll be here.",
                entries = bookmarks.map { it.title to it.url },
                onOpen = { url -> go(url); showBookmarks = false },
                onRemove = { url -> scope.launch { store.removeBookmark(url) } },
            )
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            LinkListSheet(
                title = "History",
                empty = "Nothing here yet.",
                entries = history.map { it.title.ifBlank { it.url } to it.url },
                onOpen = { url -> go(url); showHistory = false },
                onRemove = null,
                onClearAll = { scope.launch { store.clearHistory() } },
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            BrowserSettingsSheet(
                settings = settings,
                ruleCount = adBlock.ruleCount,
                onChange = { next -> scope.launch { store.saveSettings(next) } },
                onClearData = {
                    scope.launch {
                        clearBrowsingData(context)
                        store.clearHistory()
                        showSettings = false
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Private tabs are the one kind that must not survive leaving the browser:
            // their whole promise is that nothing is left behind. Close first, then
            // wipe — the profile cannot be deleted while a WebView still holds it.
            session.closeIncognito()
            wipePrivateProfile()
        }
    }
}

private fun nowMillis(): Long = System.currentTimeMillis()

/** Name of the throwaway WebView profile every private tab shares. */
private const val PRIVATE_PROFILE = "portal-private"

/**
 * Whether this device's WebView can give private tabs their own cookie jar and
 * storage. Needs WebView 114+; without it private tabs still get no history, no
 * persistent storage, no cache and no third-party cookies, but they share the
 * process-wide jar — which the UI says out loud rather than implying otherwise.
 */
private fun privateProfilesSupported(): Boolean =
    WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

/** Put [webView] on the private profile. Must happen before anything is loaded. */
private fun attachPrivateProfile(webView: WebView) {
    if (!privateProfilesSupported()) return
    runCatching {
        ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
        WebViewCompat.setProfile(webView, PRIVATE_PROFILE)
    }
}

/**
 * Delete everything the private tabs accumulated. Only possible once every WebView
 * using the profile is destroyed, which is why this runs after the tabs are closed
 * rather than alongside them.
 */
private fun wipePrivateProfile() {
    if (!privateProfilesSupported()) return
    runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
}

/** Everything WebView needs configuring, in one place so a new tab can't drift. */
@SuppressLint("SetJavaScriptEnabled")
private fun newWebView(
    ctx: Context,
    tab: BrowserTab,
    adBlock: AdBlock,
    background: Int,
    settingsProvider: () -> BrowserSettings,
    onTitle: (String?) -> Unit,
    onFullscreen: (View?) -> Unit,
    openInNewTab: (String) -> Unit,
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    // Before any settings or loading: a profile can only be assigned to a WebView
    // that has not been used yet.
    if (tab.incognito) attachPrivateProfile(this)

    val cookies = CookieManager.getInstance()
    // Third-party cookies are the cross-site tracking mechanism; off by default and
    // always off in a private tab regardless of the global setting.
    cookies.setAcceptThirdPartyCookies(
        this,
        !tab.incognito && !settingsProvider().blockThirdPartyCookies,
    )

    settings.apply {
        javaScriptEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        // With the Profile API the private tab has its own jar and storage, so DOM
        // storage can stay on (sites break without it) and is deleted with the
        // profile. Without it, storage would be the shared one — so it stays off.
        domStorageEnabled = !tab.incognito || privateProfilesSupported()
        cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        // The popup half of "popup blocker": a script cannot open a window on its own,
        // and onCreateWindow below only honours a genuine tap.
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true)
        // Leave autoplay gated. A page that starts playing on its own is the thing
        // people install a blocker to stop, and casting doesn't need local playback.
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)

        // Dark mode, the way a current browser does it: a site that ships its own dark
        // theme gets `prefers-color-scheme: dark` and renders it; a site that doesn't
        // gets WebView's own darkening. Both are driven by the *activity* theme's
        // `isLightTheme` — see values/themes.xml and values-night/themes.xml, which is
        // where "follows the system" actually comes from. Needs WebView on API 29+;
        // below that pages stay light, which is the old behaviour, not a new bug.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
        }
    }

    setBackgroundColor(background)

    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        downloadFile(ctx, url, userAgent, contentDisposition, mimeType)
    }

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            val host = tab.url?.let { AdBlock.hostOf(it) }?.let { AdBlock.siteKey(it) }
            if (adBlock.shouldBlock(url, host)) {
                post { tab.blocked = adBlock.blockedCount }
                return blockedResponse()
            }
            val kind = MediaSniffer.classify(url) ?: return null
            // Runs on a background thread; Compose state is not thread-safe, so hop
            // to the view's own thread before touching the list.
            post { addFound(tab.found, url, kind, view.title) }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val scheme = request.url.scheme?.lowercase()
            // intent:// and market:// are how a page throws you out of the browser and
            // into an app store. Not from here.
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.resetPageState()
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.url = url
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            onTitle(view.title)
            view.evaluateJavascript(FIND_VIDEOS_JS) { raw ->
                decodeJsStringArray(raw).forEach { src ->
                    if (MediaSniffer.isUnfetchable(src)) {
                        tab.mediaHidden = true
                    } else {
                        addFound(tab.found, src, MediaSniffer.classify(src) ?: "Video", view.title)
                    }
                }
            }
        }
    }

    webChromeClient = object : WebChromeClient() {
        private var customView: View? = null

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title
            onTitle(title)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback?) {
            customView = view
            onFullscreen(view)
        }

        override fun onHideCustomView() {
            customView = null
            onFullscreen(null)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera and microphone, denied by default. A browsing surface for watching
            // video has no business granting either without being asked to.
            request.deny()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // No gesture means a popunder. Refuse, and the page carries on none the wiser.
            if (!isUserGesture) return false
            // A real "open in new tab" tap. A throwaway WebView is the documented way
            // to learn the target URL, which onCreateWindow itself never provides.
            val transport = WebView(view.context)
            transport.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    inner: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    openInNewTab(request.url.toString())
                    inner.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = transport
            resultMsg.sendToTarget()
            return true
        }
    }
}

/** `evaluateJavascript` hands back a JSON *string literal*, so it needs unwrapping twice. */
private fun decodeJsStringArray(raw: String?): List<String> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    val unquoted = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\/", "/")
    val array = runCatching { JSONArray(unquoted) }.getOrNull() ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

private fun downloadFile(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    runCatching {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            setTitle(name)
            addRequestHeader("User-Agent", userAgent ?: "")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Could not start that download", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun clearBrowsingData(context: Context) {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    WebView(context).apply {
        clearCache(true)
        clearFormData()
        clearHistory()
        destroy()
    }
}

/**
 * Add [url] to [found] unless it's already there — players re-request the same
 * manifest constantly, and a sheet listing it forty times is a sheet nobody reads.
 */
private fun addFound(
    found: SnapshotStateList<FoundMedia>,
    url: String,
    kind: String,
    title: String?,
) {
    if (found.any { it.url == url }) return
    if (found.size >= 20) return
    found.add(FoundMedia(url = url, kind = kind, pageTitle = title))
}

@Composable
private fun BrowserBar(
    address: String,
    incognito: Boolean,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    foundCount: Int,
    onShowFound: () -> Unit,
    tabCount: Int,
    onShowTabs: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    shieldOn: Boolean,
    onOpenMenu: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onToggleSiteBlocking: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
            }

            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(if (incognito) "Private search or address" else "Search or type a web address")
                },
                leadingIcon = if (incognito) {
                    { Icon(Icons.Filled.VisibilityOff, contentDescription = "Private tab") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
            )

            BadgedBox(badge = { if (foundCount > 0) Badge { Text("$foundCount") } }) {
                IconButton(onClick = onShowFound, enabled = foundCount > 0) {
                    Icon(Icons.Filled.Cast, contentDescription = "Cast from this page")
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Browser menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menuOpen = false; onNewTab() },
                    )
                    DropdownMenuItem(
                        text = { Text("New private tab") },
                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
                        onClick = { menuOpen = false; onNewIncognitoTab() },
                    )
                    DropdownMenuItem(
                        text = { Text("Tabs ($tabCount)") },
                        onClick = { menuOpen = false; onShowTabs() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (bookmarked) "Remove bookmark" else "Bookmark") },
                        leadingIcon = {
                            Icon(
                                if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                null,
                            )
                        },
                        onClick = { menuOpen = false; onToggleBookmark() },
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        onClick = { menuOpen = false; onShowBookmarks() },
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        leadingIcon = { Icon(Icons.Filled.History, null) },
                        onClick = { menuOpen = false; onShowHistory() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (shieldOn) "Turn off blocking here" else "Turn on blocking here") },
                        leadingIcon = { Icon(Icons.Filled.Shield, null) },
                        onClick = { menuOpen = false; onToggleSiteBlocking() },
                    )
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { menuOpen = false; onReload() },
                    )
                    DropdownMenuItem(
                        text = { Text("Privacy & blocking") },
                        onClick = { menuOpen = false; onOpenMenu() },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSwitcher(
    session: BrowserSession,
    onSelect: (BrowserTab) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNew: (incognito: Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tabs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AssistChip(onClick = { onNew(false) }, label = { Text("New") })
            AssistChip(onClick = { onNew(true) }, label = { Text("Private") })
        }

        LazyColumn {
            items(session.tabs, key = { it.id }) { tab ->
                ListItem(
                    headlineContent = {
                        Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            if (tab.incognito) "Private tab" else tab.url.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = if (tab.incognito) {
                        { Icon(Icons.Filled.VisibilityOff, contentDescription = null) }
                    } else {
                        null
                    },
                    trailingContent = {
                        IconButton(onClick = { onClose(tab) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close ${tab.label}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun BrowserStartHint(
    incognito: Boolean,
    bookmarks: List<com.portalremote.data.Bookmark>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (incognito) "Private tab" else "Browse to a video",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (incognito) {
                if (privateProfilesSupported()) {
                    "This tab has its own cookies and storage, separate from your " +
                        "normal tabs. Nothing is kept: it's all deleted when you leave " +
                        "the browser."
                } else {
                    // Said plainly rather than implied away: this WebView is too old
                    // for separate profiles, so a login in a normal tab is visible here.
                    "No history, no stored site data, no third-party cookies — but this " +
                        "device's WebView is too old to give the tab its own cookies, so " +
                        "sites you're signed into will still know you."
                }
            } else {
                "Play it here, then tap the cast button to move it to the PC. " +
                    "Ads, trackers and popups are blocked as you go."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (bookmarks.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bookmarks) { mark ->
                    AssistChip(
                        onClick = { onOpen(mark.url) },
                        label = {
                            Text(mark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkListSheet(
    title: String,
    empty: String,
    entries: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    onRemove: ((String) -> Unit)?,
    onClearAll: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onClearAll != null && entries.isNotEmpty()) {
                AssistChip(onClick = onClearAll, label = { Text("Clear") })
            }
        }

        if (entries.isEmpty()) {
            Text(
                empty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        LazyColumn {
            items(entries, key = { it.second }) { (label, url) ->
                ListItem(
                    headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = onRemove?.let { remove ->
                        {
                            IconButton(onClick = { remove(url) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove $label")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(url) },
                )
            }
        }
    }
}

@Composable
private fun BrowserSettingsSheet(
    settings: BrowserSettings,
    ruleCount: Int,
    onChange: (BrowserSettings) -> Unit,
    onClearData: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            "Privacy & blocking",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        SettingRow(
            label = "Block ads and trackers",
            detail = "$ruleCount rules loaded",
            checked = settings.adBlockEnabled,
            onChange = { onChange(settings.copy(adBlockEnabled = it)) },
        )
        SettingRow(
            label = "Block third-party cookies",
            detail = "The cross-site tracking mechanism. Always off in private tabs.",
            checked = settings.blockThirdPartyCookies,
            onChange = { onChange(settings.copy(blockThirdPartyCookies = it)) },
        )
        SettingRow(
            label = "Save history",
            detail = "Private tabs never record, whatever this says.",
            checked = settings.saveHistory,
            onChange = { onChange(settings.copy(saveHistory = it)) },
        )

        Text(
            "Search engine",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            items(SearchEngine.entries) { engine ->
                AssistChip(
                    onClick = { onChange(settings.copy(searchEngine = engine)) },
                    label = { Text(engine.label) },
                    leadingIcon = if (engine == settings.searchEngine) {
                        { Icon(Icons.Filled.Shield, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
        }

        if (settings.allowedHosts.isNotEmpty()) {
            Text(
                "Blocking is off on: " + settings.allowedHosts.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        ListItem(
            headlineContent = { Text("Clear browsing data") },
            supportingContent = { Text("Cookies, cached files, site storage and history") },
            trailingContent = {
                IconButton(onClick = onClearData) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear browsing data")
                }
            },
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun FoundMediaSheet(
    found: List<FoundMedia>,
    mediaHidden: Boolean,
    blocked: Int,
    onCast: (FoundMedia) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            "Found on this page",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (found.isEmpty()) {
            Text(
                if (mediaHidden) {
                    "This player builds the video in the page itself, so there's no link " +
                        "to hand over. Cast the phone's screen instead."
                } else {
                    "Nothing yet — start the video playing and it'll show up here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        LazyColumn {
            items(found, key = { it.url }) { media ->
                ListItem(
                    headlineContent = {
                        Text(media.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${media.kind} · ${media.fileName}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onCast(media) }) {
                            Icon(
                                Icons.Filled.Cast,
                                contentDescription = "Cast ${media.label}",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (blocked > 0) {
            Text(
                "$blocked ad and tracker requests blocked on this page",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}
