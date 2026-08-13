package com.portalremote.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalremote.data.SavedHost
import com.portalremote.net.CastState
import com.portalremote.net.CastStatus
import com.portalremote.net.CastTarget
import com.portalremote.net.CastUrl
import com.portalremote.net.MediaApi
import com.portalremote.net.NowPlaying
import com.portalremote.net.Playhead
import com.portalremote.net.Protocol
import com.portalremote.ui.theme.HapticPress
import com.portalremote.ui.theme.rememberPressScale
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Cover art thumbnail. Big enough to recognise an album by, small enough that the
 *  transport buttons stay the thing your thumb lands on. */
private val COVER_SIZE = 72.dp

/** How often the progress bar recomputes while playing. Fast enough to read as
 *  continuous, slow enough that a screen whose real job is input isn't recomposing
 *  every frame for decoration — see docs/design-system.md §1. */
private const val PLAYHEAD_TICK_MS = 250L

/** How long a finished drag keeps the bar, waiting for the PC to catch up. The
 *  measured seek round trip is ~45ms; this is a wide margin over it. */
private const val SEEK_SETTLE_MS = 1_500L

@Composable
fun MediaScreen(
    host: SavedHost,
    nowPlaying: NowPlaying?,
    onMedia: (action: String) -> Unit,
    onSeek: (ms: Long) -> Unit,
    cast: CastState?,
    castStatus: CastStatus?,
    castTargets: List<CastTarget>,
    castTarget: String?,
    castScanning: Boolean,
    onCastTarget: (String?) -> Unit,
    onScanCastTargets: () -> Unit,
    onCast: (url: String) -> Unit,
    onCastFile: (Uri) -> String?,
    onPlayer: (JSONObject) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CastTargets(castTargets, castTarget, castScanning, onCastTarget, onScanCastTargets)

        CastLink(onCast, onCastFile)

        cast?.let {
            Spacer(Modifier.height(16.dp))
            CastTransport(
                cast = it,
                status = castStatus,
                // Unknown target means one of the PC's own routes, which all seek. A
                // Roku is the case this exists for: its entire control protocol is the
                // physical remote's buttons, so there is nothing to drag towards.
                canSeek = castTargets.firstOrNull { t -> t.id == it.target }?.seek ?: true,
                onPlayer = onPlayer,
            )
        }

        Spacer(Modifier.height(24.dp))

        NowPlayingCard(host = host, state = nowPlaying, onSeek = onSeek)

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                icon = Icons.Filled.SkipPrevious,
                description = "Previous",
                size = 56.dp,
                // Only when the PC has told us it can't — an unknown state (nothing
                // playing yet, older server) leaves the buttons usable, since the
                // media keys work whether or not this card knows about it.
                enabled = nowPlaying?.canPrev ?: true,
                onClick = { onMedia("prev") },
            )

            TransportButton(
                // The PC knows whether it's playing, so this stops being a guess: a
                // button showing "play" while music is playing is the one bit of this
                // screen that was always slightly wrong.
                icon = if (nowPlaying?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (nowPlaying?.playing == true) "Pause" else "Play",
                size = 72.dp,
                filled = true,
                onClick = { onMedia("play_pause") },
            )

            TransportButton(
                icon = Icons.Filled.SkipNext,
                description = "Next",
                size = 56.dp,
                enabled = nowPlaying?.canNext ?: true,
                onClick = { onMedia("next") },
            )
        }

        Text("Volume", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TransportButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", onClick = { onMedia("mute") })
            TransportButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", onClick = { onMedia("vol_down") })
            TransportButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", onClick = { onMedia("vol_up") })
        }
    }
}

/**
 * What the PC is playing: cover, title, artist/album, and a bar that moves.
 *
 * The bar is interpolated locally between the server's pushes — see
 * [NowPlaying.positionAt] — so it advances smoothly instead of stepping twice a
 * second, and costs nothing on the wire to do so. It doubles as the scrubber where
 * the player allows seeking; while a drag is in progress the finger wins over the
 * incoming position, or the bar would fight the thumb.
 */
@Composable
private fun NowPlayingCard(host: SavedHost, state: NowPlaying?, onSeek: (Long) -> Unit) {
    if (state == null) {
        Text(
            "Nothing playing on the PC",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp),
        )
        return
    }

    var dragging by remember { mutableStateOf<Float?>(null) }
    // The dragged value outranks the incoming one, then is released on a timer.
    // Not released on the next push instead: the push landing right after a drag can
    // still carry the pre-seek position, and snapping back to it for a frame reads as
    // the seek having failed. Not held until a matching push either — a player that
    // ignores the seek would pin the bar forever, which is exactly what happened
    // before this was a timer. Each drag restarts the wait, so it only runs from the
    // last movement.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        dragging = null
    }

    val position = rememberPlayhead(state)
    val shown = dragging?.toLong() ?: position

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(host = host, revision = state.art)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.title ?: "Unknown track",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                // Scrolled rather than ellipsized: a track title is the one string on
                // this screen where the end matters as much as the start.
                modifier = Modifier.basicMarquee(),
            )
            state.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
            }
            state.app?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // A live stream has no end to scrub towards, so it gets the times only.
    if (state.durationMs > 0) {
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                // `dragging` is deliberately not cleared here — the effect above owns
                // releasing it once the PC has had time to catch up.
                dragging?.let { onSeek(it.toLong()) }
            },
            valueRange = 0f..state.durationMs.toFloat(),
            enabled = state.canSeek,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatTime(shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.durationMs > 0) {
            Text(
                formatTime(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The playhead, ticking. Only while something is actually playing — a paused track
 * has one position and no reason to recompose.
 */
@Composable
private fun rememberPlayhead(state: Playhead): Long {
    var position by remember(state) { mutableLongStateOf(state.positionAt(SystemClock.elapsedRealtime())) }
    LaunchedEffect(state) {
        while (state.playing) {
            delay(PLAYHEAD_TICK_MS)
            position = state.positionAt(SystemClock.elapsedRealtime())
        }
    }
    return position
}

/** The cover, fetched over HTTP and held until [revision] says it's a different one. */
@Composable
private fun CoverArt(host: SavedHost, revision: Int?) {
    val api = remember { MediaApi() }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(host, revision) {
        art = revision?.let { api.art(host, it) }
    }

    val shape = RoundedCornerShape(12.dp)
    val bitmap = art
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .size(COVER_SIZE)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            // The title next to it says what this is; announcing it twice is noise.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(COVER_SIZE)
                .clip(shape),
        )
    }
}

/** `m:ss`, or `h:mm:ss` once there's an hour to show. */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * Phase 4a of `docs/phase4-casting.md`: paste a media link, the PC opens it. This is
 * the throwaway front end for the handoff — the browser that finds these links for
 * you is 4e, and it will send the same message.
 */
/** How long the "sent it" line stays up. Long enough to read, short enough that it's
 *  gone before it starts reading as the current state of anything. */
private const val CAST_ACK_MS = 4_000L

/**
 * Where the next cast goes — steps 4c/4k of `docs/phase4-casting.md`.
 *
 * A chip row rather than a sheet, for the same reason the mirror's monitor picker is
 * one (`docs/design-system.md` §7): it is a setting you change *while* looking at the
 * thing it affects, and a dialog would put a modal between the two.
 *
 * "This PC" is the unselected state, not a chip of its own — leaving it alone hands the
 * choice back to the PC, which prefers an open receiver page, then mpv, then whatever
 * the desktop has registered. The list is asked for on first look rather than kept
 * fresh: SSDP is multicast chatter, and a TV that is off is not going to appear on its
 * own anyway.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
private fun CastTargets(
    targets: List<CastTarget>,
    chosen: String?,
    scanning: Boolean,
    onChoose: (String?) -> Unit,
    onScan: () -> Unit,
) {
    // Once per visit to this screen. A scan costs a few seconds of the PC's time and a
    // burst of multicast; the PC caches it, so reopening the tab is cheap.
    LaunchedEffect(Unit) { onScan() }

    // Anything beyond the PC's own three routes. With none found there is nothing to
    // pick between, so the row stays out of the way entirely.
    val remote = targets.filter { it.kind !in LOCAL_KINDS }
    if (remote.isEmpty() && !scanning) return

    Text("Cast to", style = MaterialTheme.typography.titleMedium)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = chosen == null,
            onClick = { onChoose(null) },
            leadingIcon = { Icon(Icons.Filled.Computer, contentDescription = null) },
            label = { Text("This PC") },
        )
        remote.forEach { target ->
            FilterChip(
                selected = chosen == target.id,
                onClick = { onChoose(target.id) },
                leadingIcon = { Icon(Icons.Filled.Tv, contentDescription = null) },
                label = { Text(target.name) },
            )
        }
        // Not a spinner: the row is already useful, and this is the one line that
        // distinguishes "nothing on this network" from "still looking".
        if (scanning) {
            Text(
                "Looking for screens…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            TextButton(onClick = onScan) { Text("Scan again") }
        }
    }
}

/** The PC's own three routes, which the "This PC" chip stands for collectively. */
private val LOCAL_KINDS = setOf(CastState.RECEIVER, CastState.MPV, CastState.SHELL)

@Composable
private fun CastLink(onCast: (url: String) -> Unit, onCastFile: (Uri) -> String?) {
    var typed by remember { mutableStateOf("") }
    var lastSent by remember { mutableStateOf<String?>(null) }
    // Normalising as the user types is also the enabled-state check: if it can't be
    // made into a URL there is nothing to send.
    val url = CastUrl.normalize(typed)
    val submit = {
        url?.let {
            onCast(it)
            lastSent = it
            typed = ""
        }
        Unit
    }

    Text("Cast a link", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it; lastSent = null },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Paste a video link") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        TransportButton(
            icon = Icons.Filled.Cast,
            description = "Cast link",
            filled = true,
            enabled = url != null,
            onClick = submit,
        )
    }
    CastFile(onCastFile)
    // An acknowledgement, not a status: the PC has it, and a line still saying so ten
    // minutes later is claiming to describe the present.
    LaunchedEffect(lastSent) {
        if (lastSent == null) return@LaunchedEffect
        delay(CAST_ACK_MS)
        lastSent = null
    }
    lastSent?.let {
        Text(
            // Not "on the PC" any more: since the target picker, this may be heading for
            // a television. Where it actually landed is named by the Casting card below,
            // which knows because the server said so — this line only has to not lie in
            // the moment before that arrives.
            "Sent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Phase 4d: cast a file that lives on this phone. The phone serves it and the PC pulls
 * it, so this is a link like any other by the time it reaches the wire — nothing is
 * uploaded, and a two-hour film starts playing immediately.
 */
@Composable
private fun CastFile(onCastFile: (Uri) -> String?) {
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) problem = onCastFile(uri)
    }

    TextButton(
        onClick = {
            problem = null
            // Audio and images too: the receiver page and mpv both take whatever the
            // format is, and "video only" would be an arbitrary restriction.
            picker.launch(arrayOf("video/*", "audio/*", "image/*"))
        },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text("Cast a file from this phone", modifier = Modifier.padding(start = 8.dp))
    }
    // Every reason this fails is something the user can act on — a lost connection, an
    // address the PC can't reach, a document with no length — so none of them are worth
    // swallowing.
    problem?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** How far the skip buttons jump, in seconds. The usual podcast/player pair: back a
 *  sentence, forward an ad break. */
private const val SKIP_BACK_SECONDS = -10.0
private const val SKIP_FORWARD_SECONDS = 30.0

/**
 * Transport for the link this phone cast, driving the receiver page directly.
 *
 * Deliberately not the row below it: those are the PC's global media keys, which land
 * on whatever Windows thinks is playing — pausing Spotify while the film rolls on is
 * the failure §8 of docs/phase4-casting.md exists to avoid.
 *
 * The receiver reports position, duration and paused state on every transport event
 * plus once a second while playing, and the PC forwards each one (4b of
 * docs/phase4-casting.md). So this is a real scrub bar and a toggle that knows which
 * way it points — but only once something has actually reported. Until then, and for a
 * receiver that never does, it falls back to the blind buttons: a bar pinned at zero
 * would claim a position we don't have.
 */
@Composable
internal fun CastTransport(
    cast: CastState,
    status: CastStatus?,
    canSeek: Boolean,
    onPlayer: (JSONObject) -> Unit,
) {
    // Naming the screen matters the moment there is more than one: "Casting" alone
    // leaves the user to work out whether the last press went to the TV or the PC.
    Text(
        cast.targetName?.let { "Casting to $it" } ?: "Casting",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        cast.label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .basicMarquee(),
    )

    if (!cast.controllable) {
        // The PC threw the link at whatever is registered for it and kept no handle on
        // it, so say what to do about it rather than showing buttons that would error.
        Text(
            "Opened in the PC's own player, which can't be controlled from here. Open "
                + "the cast receiver page on the screen you want first — the address is "
                + "in the Portal Remote window on the PC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    status?.let { CastProgress(it, canSeek, onPlayer) }

    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Icons.Filled.Replay10, "Back 10 seconds") {
            onPlayer(Protocol.playerSeekBy(SKIP_BACK_SECONDS))
        }
        when {
            // Nothing has reported yet, so there is no state for a toggle to point at
            // — two buttons that each mean one thing beat one that might mean either.
            status == null -> {
                TransportButton(Icons.Filled.PlayArrow, "Play", filled = true) {
                    onPlayer(Protocol.player("play"))
                }
                TransportButton(Icons.Filled.Pause, "Pause") { onPlayer(Protocol.player("pause")) }
            }
            // Explicit play/pause rather than the receiver's `toggle`: the state shown
            // here is a report that may be a second old, and a toggle against a stale
            // reading flips the wrong way. Sending the action we're displaying is
            // idempotent when we're wrong.
            status.playing -> TransportButton(Icons.Filled.Pause, "Pause", filled = true) {
                onPlayer(Protocol.player("pause"))
            }
            else -> TransportButton(Icons.Filled.PlayArrow, "Play", filled = true) {
                onPlayer(Protocol.player("play"))
            }
        }
        TransportButton(Icons.Filled.Forward30, "Forward 30 seconds") {
            onPlayer(Protocol.playerSeekBy(SKIP_FORWARD_SECONDS))
        }
        TransportButton(Icons.Filled.Stop, "Stop casting") { onPlayer(Protocol.player("stop")) }
    }

    status?.let { CastHint(it) }
}

/**
 * The cast scrub bar. Same shape as the now-playing one and for the same reasons — the
 * position is interpolated locally between reports, and a finger on the thumb outranks
 * an incoming position until a settle timer releases it, or the bar fights the drag.
 *
 * A live stream reports no duration and gets the elapsed time only: there is no end to
 * scrub towards, and a full-width bar would imply one. Same treatment for a receiver
 * with no absolute seek in its protocol ([canSeek]) — the times still tick, because
 * knowing where you are is useful even when you cannot change it.
 */
@Composable
private fun CastProgress(status: CastStatus, canSeek: Boolean, onPlayer: (JSONObject) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        dragging = null
    }

    val position = rememberPlayhead(status)
    val shown = dragging?.toLong() ?: position

    if (status.seekable && canSeek) {
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onPlayer(Protocol.playerSeekTo(it.toDouble() / 1000)) }
            },
            valueRange = 0f..status.durationMs.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatTime(shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.seekable) {
            Text(
                formatTime(status.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two states where pressing a button will visibly do nothing, said out loud.
 *
 * A browser will not begin playback a user never asked for, and a command arriving over
 * a socket carries no user activation — so a receiver page needs one press on the
 * receiving screen before anything here works. Without this the cast just looks broken,
 * which is exactly the confusion §13 of docs/phase4-casting.md records.
 */
@Composable
private fun CastHint(status: CastStatus) {
    val message = when {
        status.waitingForGesture ->
            "Press the button on the screen you're casting to — a browser won't start " +
                "playback until the page has been touched once."
        // HTMLMediaElement.error codes. 4 is the common one and the only one with a
        // useful answer, so it gets its own wording rather than a number.
        status.errorCode == 4 ->
            "That screen can't play this format. Cast with no receiver page open and it " +
                "will land in the PC's own player instead."
        status.errorCode != 0 ->
            "The receiver reported a playback error (code ${status.errorCode})."
        else -> return
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Transport/volume button with the standard 100-120ms press-scale feedback — see
 * docs/design-system.md §6/§7. */
@Composable
internal fun TransportButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interactionSource)
    HapticPress(interactionSource)
    val modifier = Modifier
        .size(size)
        .graphicsLayer { scaleX = scale; scaleY = scale }

    if (filled) {
        FilledIconButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(icon, contentDescription = description)
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}
