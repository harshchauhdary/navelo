package app.navelo.tv

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import app.navelo.shared.ItemType
import app.navelo.shared.MediaItem
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import androidx.tv.material3.Button as TvButton

/** Full-screen Media3 player used by the TV UI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun NaveloPlayer(
    item: MediaItem,
    repository: TvRepository,
    startOver: Boolean,
    onBack: () -> Unit,
    onNext: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repositoryState by repository.state.collectAsStateWithLifecycle()
    val latestBack by rememberUpdatedState(onBack)
    val latestNext by rememberUpdatedState(onNext)
    val scope = rememberCoroutineScope()
    val mediaUrl = repository.mediaUrl(item.id)
    val token = repository.authToken()
    val subtitles = remember(item.id, repositoryState.items) {
        SubtitleAssociation.forVideo(item, repositoryState.items)
    }
    val resumeState = remember(item.id) {
        PlaybackResumeState(repositoryState.watch[item.id]?.positionMs ?: 0L)
    }

    if (mediaUrl == null || token.isNullOrBlank()) {
        BackHandler { latestBack() }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("Pair with the phone to play this video", color = Color.White)
        }
        return
    }

    val playbackClient = remember(item.id, token) {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    val player = remember(item.id, mediaUrl, token, subtitles, playbackClient) {
        resumeState.activePlayer?.let { previous ->
            if (previous.currentPosition >= 0) resumeState.positionMs = previous.currentPosition
        }
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "User-Agent" to "Navelo-TV/1.0",
        )
        val httpFactory = OkHttpDataSource.Factory(playbackClient)
            .setDefaultRequestProperties(headers)
        val sourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        val externalSubtitles = subtitles.mapNotNull { subtitle ->
            val url = repository.mediaUrl(subtitle.id) ?: return@mapNotNull null
            PlayerMediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(SubtitleAssociation.mimeType(subtitle))
                .setLanguage(SubtitleAssociation.language(subtitle))
                .setLabel(SubtitleAssociation.label(subtitle))
                .setSelectionFlags(if (subtitles.size == 1) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(sourceFactory)
            .build()
            .apply {
                setHandleAudioBecomingNoisy(true)
                setMediaItem(
                    PlayerMediaItem.Builder()
                        .setMediaId(item.id)
                        .setUri(mediaUrl)
                        .setSubtitleConfigurations(externalSubtitles)
                        .build(),
                )
                val resumePosition = when {
                    resumeState.firstBuild && (startOver || repositoryState.watch[item.id]?.watched == true) -> 0L
                    else -> resumeState.positionMs
                }
                resumeState.firstBuild = false
                seekTo(resumePosition)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !repositoryState.settings.subtitles)
                    .build()
                prepare()
                playWhenReady = true
            }
            .also { resumeState.activePlayer = it }
    }
    val mediaSession = remember(player) { MediaSession.Builder(context, player).setId("navelo-${java.util.UUID.randomUUID()}").build() }

    var ended by remember(item.id) { mutableStateOf(false) }
    var countdown by remember(item.id) { mutableIntStateOf(0) }
    var recoveryMessage by remember(item.id) { mutableStateOf<String?>(null) }
    var retryAvailable by remember(item.id) { mutableStateOf(false) }
    var retryCount by remember(item.id) { mutableIntStateOf(0) }
    var retryJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var autoNextCancelled by remember(item.id) { mutableStateOf(false) }
    val playNowFocus = remember(item.id) { FocusRequester() }
    val retryFocus = remember(item.id) { FocusRequester() }
    val nextEpisode = remember(item.id, repositoryState.items) { repository.nextEpisode(item) }
    var hostedPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    val overlayHasFocus by rememberUpdatedState(countdown > 0 || recoveryMessage != null)

    // Some TV/Compose versions move window focus out of AndroidView when its
    // focused transport button becomes GONE. Route remote keys at the activity
    // boundary so Media3 can show its controls even after that focus loss.
    DisposableEffect(hostedPlayerView, context) {
        val view = hostedPlayerView
        val activity = context.naveloActivity()
        var consumeBackUp = false
        val handler: (KeyEvent) -> Boolean = { event ->
            when {
                overlayHasFocus || view == null -> false
                event.keyCode == KeyEvent.KEYCODE_BACK && consumeBackUp -> {
                    if (event.action == KeyEvent.ACTION_UP) consumeBackUp = false
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_BACK && view.isControllerFullyVisible -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        view.hideController()
                        view.requestFocus()
                        consumeBackUp = true
                    }
                    true
                }
                PlayerRemoteKeys.handledByPlayer(event.keyCode) -> view.dispatchKeyEvent(event)
                else -> false
            }
        }
        if (view != null) activity?.playbackKeyHandler = handler
        onDispose {
            if (activity?.playbackKeyHandler === handler) activity.playbackKeyHandler = null
        }
    }

    fun persist(playing: Boolean = player.isPlaying) {
        val duration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 }
            ?: repository.state.value.watch[item.id]?.durationMs ?: 0L
        resumeState.positionMs = player.currentPosition.coerceAtLeast(0)
        repository.saveProgress(item.id, resumeState.positionMs, duration)
        repository.reportPlayback(item.id, resumeState.positionMs, duration, playing)
    }

    BackHandler {
        persist(false)
        latestBack()
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    recoveryMessage = null
                    retryAvailable = false
                    retryCount = 0
                }
                if (playbackState != Player.STATE_ENDED) autoNextCancelled = false
            }

            override fun onPlayerError(error: PlaybackException) {
                persist(false)
                retryJob?.cancel()
                var cause: Throwable? = error
                var httpStatus: Int? = null
                while (cause != null) {
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        httpStatus = cause.responseCode; break
                    }
                    cause = cause.cause
                }
                if (httpStatus == 404 || httpStatus == 401 || httpStatus == 403 || httpStatus == 503) {
                    recoveryMessage = when (httpStatus) {
                        404 -> "This video is no longer available."
                        401, 403 -> "Connect this TV to your phone again in Settings."
                        else -> "This video is temporarily unavailable. Check the media folder on your phone."
                    }
                    retryAvailable = httpStatus == 503
                    return
                }
                val networkFailure = error.errorCode in 2_000..2_999
                if (!networkFailure) {
                    recoveryMessage = if (error.errorCode in 3_000..4_999) {
                        "This video format isn’t supported on this TV"
                    } else {
                        "This video couldn’t be played"
                    }
                    retryAvailable = false
                    return
                }
                if (retryCount >= 5) {
                    recoveryMessage = "Playback stopped. Press play to retry"
                    retryAvailable = true
                    return
                }
                retryCount += 1
                recoveryMessage = "Connection lost. Reconnecting…"
                retryJob = scope.launch {
                    delay((1_000L shl (retryCount - 1)).coerceAtMost(12_000L))
                    repository.refresh()
                    player.prepare()
                    player.play()
                }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    persist(false)
                    player.pause()
                }
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            retryJob?.cancel()
            persist(false)
            player.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            mediaSession.release()
            if (resumeState.activePlayer === player) resumeState.activePlayer = null
            player.release()
        }
    }

    DisposableEffect(playbackClient) {
        onDispose {
            playbackClient.dispatcher.executorService.shutdown()
            playbackClient.connectionPool.evictAll()
        }
    }

    LaunchedEffect(player, repositoryState.settings.subtitles) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !repositoryState.settings.subtitles)
            .build()
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.playbackState != Player.STATE_IDLE) persist()
        }
    }

    LaunchedEffect(ended, nextEpisode?.id, repositoryState.settings.autoNext, autoNextCancelled) {
        if (!ended || nextEpisode == null || !repositoryState.settings.autoNext || autoNextCancelled) {
            countdown = 0
            return@LaunchedEffect
        }
        for (second in 10 downTo 1) {
            countdown = second
            delay(1_000)
        }
        countdown = 0
        persist(false)
        latestNext(nextEpisode)
    }

    LaunchedEffect(countdown) {
        if (countdown == 10) {
            delay(80)
            runCatching { playNowFocus.requestFocus() }
        }
    }

    LaunchedEffect(retryAvailable) {
        if (retryAvailable) {
            delay(80)
            runCatching { retryFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = false
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        if (visibility != View.VISIBLE && !overlayHasFocus) requestFocus()
                    })
                    post { requestFocus(); showController() }
                    hostedPlayerView = this
                }
            },
            update = { it.player = player },
        )

        recoveryMessage?.let { message ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(28.dp)
                    .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(text = message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (retryAvailable) {
                    TvButton(
                        onClick = {
                            retryAvailable = false
                            recoveryMessage = "Reconnecting…"
                            retryCount = 0
                            player.prepare()
                            player.play()
                        },
                        modifier = Modifier.padding(start = 16.dp).focusRequester(retryFocus),
                    ) { Text("Retry") }
                }
            }
        }

        if (countdown > 0 && nextEpisode != null) {
            val nextTitle = repositoryState.metadata[nextEpisode.id]?.episodeTitle
                ?: nextEpisode.displayName
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(36.dp)
                    .background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Next: $nextTitle in $countdown",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TvButton(
                        onClick = {
                            persist(false)
                            latestNext(nextEpisode)
                        },
                        modifier = Modifier.padding(start = 16.dp).focusRequester(playNowFocus),
                    ) { Text("Play now") }
                    TvButton(
                        onClick = {
                            autoNextCancelled = true
                            countdown = 0
                        },
                        modifier = Modifier.padding(start = 10.dp),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

private fun Context.naveloActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.naveloActivity()
    else -> null
}

internal object PlayerRemoteKeys {
    private val keys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
    )
    fun handledByPlayer(code: Int): Boolean = code in keys
}

private class PlaybackResumeState(
    var positionMs: Long,
    var firstBuild: Boolean = true,
    var activePlayer: ExoPlayer? = null,
)

internal object SubtitleAssociation {
    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")
    private val languageSuffix = Regex("[._ -](en|eng|es|spa|fr|fre|fra|de|ger|deu|it|ita|pt|por|ja|jpn|ko|kor|hi|hin|forced|sdh|cc)$")

    fun forVideo(video: MediaItem, items: List<MediaItem>): List<MediaItem> {
        val videoBase = baseName(video.filename)
        return items.asSequence()
            .filter { it.type == ItemType.SUBTITLE || it.extension.lowercase(Locale.ROOT) in subtitleExtensions }
            .filter { subtitle ->
                val sameFolder = when {
                    video.parentId != null || subtitle.parentId != null -> video.parentId == subtitle.parentId
                    else -> video.relativePath.substringBeforeLast('/', "") == subtitle.relativePath.substringBeforeLast('/', "")
                }
                subtitle.rootId == video.rootId && sameFolder && normalizedSubtitleBase(subtitle.filename) == videoBase
            }
            .sortedBy { it.filename.lowercase(Locale.ROOT) }
            .toList()
    }

    fun mimeType(item: MediaItem): String = when (item.extension.lowercase(Locale.ROOT).ifBlank {
        item.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun label(item: MediaItem): String {
        val language = language(item)
        val name = if (language == null) "Subtitles" else Locale.forLanguageTag(language).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }
        return name + if (item.filename.contains("forced", true)) " (forced)" else ""
    }

    fun language(item: MediaItem): String? {
        val name = item.filename.substringBeforeLast('.')
        return Regex("(?:[._ -])(en|eng|es|spa|fr|fre|fra|de|ger|deu|it|ita|pt|por|ja|jpn|ko|kor|hi|hin)(?:[._ -](?:forced|sdh|cc))?$", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)
    }

    private fun normalizedSubtitleBase(filename: String): String {
        var value = baseName(filename)
        repeat(2) { value = value.replace(languageSuffix, "") }
        return value.trimEnd('.', '_', '-', ' ')
    }

    private fun baseName(filename: String): String = filename.substringBeforeLast('.', filename)
        .lowercase(Locale.ROOT)
        .trim()
}
