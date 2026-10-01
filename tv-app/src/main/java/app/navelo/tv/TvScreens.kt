package app.navelo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.navelo.shared.MediaItem
import app.navelo.shared.ItemType
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private sealed interface TvDestination {
    data object Home : TvDestination
    data object Search : TvDestination
    data object Settings : TvDestination
    data object FixMatch : TvDestination
    data class Movie(val itemId: String) : TvDestination
    data class Show(val showKey: String, val initialEpisodeId: String? = null) : TvDestination
    data class Player(val itemId: String, val startOver: Boolean) : TvDestination
}

private data class HomeRows(
    val continueWatching: List<MediaItem>,
    val recentlyAdded: List<MediaItem>,
    val movies: List<MediaItem>,
    val shows: List<MediaItem>,
    val recentlyWatched: List<MediaItem>,
)

@Composable
fun NaveloTvApp(repository: TvRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf<TvDestination>(TvDestination.Home) }
    var homeFocusId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlayId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStartOver by rememberSaveable { mutableStateOf(false) }
    var wasPaired by remember { mutableStateOf(state.paired) }

    LaunchedEffect(state.paired) {
        if (!wasPaired && state.paired) destination = TvDestination.Home
        wasPaired = state.paired
    }

    BackHandler(enabled = destination != TvDestination.Home) {
        destination = when (destination) {
            is TvDestination.Player, is TvDestination.Movie, is TvDestination.Show,
            TvDestination.Search, TvDestination.Settings, TvDestination.FixMatch -> TvDestination.Home
            TvDestination.Home -> TvDestination.Home
        }
    }

    LaunchedEffect(state.online, pendingPlayId) {
        val itemId = pendingPlayId
        if (state.online && itemId != null) {
            pendingPlayId = null
            destination = TvDestination.Player(itemId, startOver = pendingStartOver)
        }
    }

    CompositionLocalProvider(LocalTvRepository provides repository) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF123345), NaveloNavy),
                        center = Offset(280f, 120f),
                        radius = 1250f,
                    )
                )
        ) {
            if (!state.paired && state.items.isEmpty()) {
                PairingScreen(state = state, onConnect = repository::connect)
            } else {
                when (val screen = destination) {
                    TvDestination.Home -> HomeScreen(
                        state = state,
                        restoreFocusId = homeFocusId,
                        onCardFocus = { homeFocusId = it },
                        onOpenItem = { item ->
                            destination = if (item.isEpisode()) {
                                TvDestination.Show(item.showKey(), item.id)
                            } else {
                                TvDestination.Movie(item.id)
                            }
                        },
                        onSearch = { destination = TvDestination.Search },
                        onSettings = { destination = TvDestination.Settings },
                    )
                    TvDestination.Search -> SearchScreen(
                        state = state,
                        onBack = { destination = TvDestination.Home },
                        onOpenItem = { item ->
                            destination = if (item.isEpisode()) TvDestination.Show(item.showKey(), item.id) else TvDestination.Movie(item.id)
                        },
                    )
                    TvDestination.Settings -> SettingsScreen(
                        state = state,
                        repository = repository,
                        onBack = { destination = TvDestination.Home },
                        onFixMatch = { destination = TvDestination.FixMatch },
                    )
                    TvDestination.FixMatch -> FixMatchScreen(
                        state = state,
                        repository = repository,
                        onBack = { destination = TvDestination.Settings },
                    )
                    is TvDestination.Movie -> {
                        val item = state.items.firstOrNull { it.id == screen.itemId }
                        if (item == null) {
                            LaunchedEffect(screen.itemId) { destination = TvDestination.Home }
                        } else {
                            MovieDetailScreen(
                                item = item,
                                state = state,
                                onBack = { destination = TvDestination.Home },
                                onPlay = { startOver ->
                                    if (state.online) destination = TvDestination.Player(item.id, startOver)
                                    else { pendingStartOver = startOver; pendingPlayId = item.id }
                                },
                            )
                        }
                    }
                    is TvDestination.Show -> ShowDetailScreen(
                        showKey = screen.showKey,
                        initialEpisodeId = screen.initialEpisodeId,
                        state = state,
                        onBack = { destination = TvDestination.Home },
                        onPlay = { item, startOver ->
                            if (state.online) destination = TvDestination.Player(item.id, startOver)
                            else { pendingStartOver = startOver; pendingPlayId = item.id }
                        },
                    )
                    is TvDestination.Player -> {
                        val item = state.items.firstOrNull { it.id == screen.itemId }
                        if (item == null) {
                            LaunchedEffect(screen.itemId) { destination = TvDestination.Home }
                        } else {
                            NaveloPlayer(
                                item = item,
                                repository = repository,
                                startOver = screen.startOver,
                                onBack = {
                                    destination = if (item.isEpisode()) TvDestination.Show(item.showKey(), item.id) else TvDestination.Movie(item.id)
                                },
                                onNext = { next -> destination = TvDestination.Player(next.id, startOver = true) },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = pendingPlayId != null && !state.online,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                OfflinePlayPanel(
                    onRetry = repository::refresh,
                    onDismiss = { pendingPlayId = null },
                )
            }
        }
    }
}

@Composable
private fun PairingScreen(state: TvState, onConnect: (app.navelo.shared.DiscoveredServer) -> Unit) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(state.discovered, state.connecting) {
        if (state.discovered.isNotEmpty() && !state.connecting && state.pairingPin == null) {
            withFrameNanos { }
            firstButton.requestFocus()
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp),
    ) {
        BrandMark(modifier = Modifier.size(188.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Your library is close by", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            when {
                state.pairingPin != null -> {
                    Text("Approve this TV on your media phone", style = MaterialTheme.typography.headlineSmall, color = NaveloMint)
                    Text(
                        "Check that this number appears on both screens",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NaveloMuted,
                    )
                    Text(
                        state.pairingPin.chunked(3).joinToString("  "),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                        color = NaveloAmber,
                    )
                    Text("Waiting for approval…", style = MaterialTheme.typography.titleMedium, color = NaveloMuted)
                }
                state.connecting -> {
                    Text("Connecting…", style = MaterialTheme.typography.headlineSmall, color = NaveloMint)
                    Text("Keep Navelo open on your media phone for a moment.", style = MaterialTheme.typography.bodyLarge, color = NaveloMuted)
                }
                state.discovered.isEmpty() -> {
                    Text("Looking for your media phone…", style = MaterialTheme.typography.headlineSmall, color = NaveloMint)
                    Text(
                        "Use the same Wi-Fi on both devices, or connect this TV to your phone’s hotspot. Navelo will connect automatically.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NaveloMuted,
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                }
                else -> {
                    Text("Navelo found", style = MaterialTheme.typography.titleLarge, color = NaveloMint)
                    state.discovered.forEachIndexed { index, server ->
                        Button(
                            onClick = { onConnect(server) },
                            modifier = Modifier.then(if (index == 0) Modifier.focusRequester(firstButton) else Modifier),
                        ) {
                            Icon(Icons.Rounded.Tv, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Connect to ${server.displayName}")
                        }
                    }
                }
            }
            state.message?.let { StatusMessage(it) }
        }
    }
}

@Composable
private fun HomeScreen(
    state: TvState,
    restoreFocusId: String?,
    onCardFocus: (String) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val rows = remember(state.items, state.watch) { buildHomeRows(state) }
    val allIds = remember(rows) {
        buildSet {
            rows.continueWatching.forEach { add("Continue Watching:${it.id}") }
            rows.recentlyAdded.forEach { add("Recently Added:${it.id}") }
            rows.movies.forEach { add("Movies:${it.id}") }
            rows.shows.forEach { add("TV Shows:${it.id}") }
            rows.recentlyWatched.forEach { add("Recently Watched:${it.id}") }
        }
    }
    val initialToken = remember(rows) {
        rows.continueWatching.firstOrNull()?.let { "Continue Watching:${it.id}" }
            ?: rows.recentlyAdded.firstOrNull()?.let { "Recently Added:${it.id}" }
            ?: rows.movies.firstOrNull()?.let { "Movies:${it.id}" }
            ?: rows.shows.firstOrNull()?.let { "TV Shows:${it.id}" }
            ?: rows.recentlyWatched.firstOrNull()?.let { "Recently Watched:${it.id}" }
    }
    val effectiveRestore = restoreFocusId?.takeIf(allIds::contains) ?: initialToken
    val visibleSections = remember(rows) {
        buildList {
            if (rows.continueWatching.isNotEmpty()) add("Continue Watching")
            if (rows.recentlyAdded.isNotEmpty()) add("Recently Added")
            if (rows.movies.isNotEmpty()) add("Movies")
            if (rows.shows.isNotEmpty()) add("TV Shows")
            if (rows.recentlyWatched.isNotEmpty()) add("Recently Watched")
        }
    }
    val restoreSection = effectiveRestore?.substringBefore(':')
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = visibleSections.indexOf(restoreSection).coerceAtLeast(0),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        HomeHeader(state = state, onSearch = onSearch, onSettings = onSettings)
        if (state.items.none { it.type == ItemType.VIDEO }) {
            EmptyLibrary(state.online, onRefresh = onSettings)
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (rows.continueWatching.isNotEmpty()) {
                    item("continue") {
                        MediaRow(
                            title = "Continue Watching",
                            items = rows.continueWatching,
                            state = state,
                            landscape = true,
                            restoreFocusId = effectiveRestore,
                            onCardFocus = onCardFocus,
                            onOpenItem = onOpenItem,
                        )
                    }
                }
                if (rows.recentlyAdded.isNotEmpty()) {
                    item("recent") {
                        MediaRow("Recently Added", rows.recentlyAdded, state, false, effectiveRestore, onCardFocus, onOpenItem)
                    }
                }
                if (rows.movies.isNotEmpty()) {
                    item("movies") {
                        MediaRow("Movies", rows.movies, state, false, effectiveRestore, onCardFocus, onOpenItem)
                    }
                }
                if (rows.shows.isNotEmpty()) {
                    item("shows") {
                        MediaRow("TV Shows", rows.shows, state, false, effectiveRestore, onCardFocus, onOpenItem)
                    }
                }
                if (rows.recentlyWatched.isNotEmpty()) {
                    item("watched") {
                        MediaRow("Recently Watched", rows.recentlyWatched, state, true, effectiveRestore, onCardFocus, onOpenItem)
                    }
                }
            }
        }
    }
}

private fun buildHomeRows(state: TvState): HomeRows {
    val videos = state.items.asSequence().filter { it.type == ItemType.VIDEO }.toList()
    val continuing = videos.filter { item ->
        state.watch[item.id]?.let { it.positionMs > 10_000 && !it.watched && it.durationMs > 0 } == true
    }.sortedByDescending { state.watch[it.id]?.lastPlayed ?: 0 }.take(24)
    val movies = videos.filterNot(MediaItem::isEpisode).sortedBy { humanTitle(it, state).lowercase(Locale.getDefault()) }
    val episodes = videos.filter(MediaItem::isEpisode)
    val shows = episodes.groupBy(MediaItem::showKey).values.mapNotNull { group ->
        group.minWithOrNull(compareBy<MediaItem> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
    }.sortedBy { humanTitle(it, state).lowercase(Locale.getDefault()) }
    val recentlyAdded = (movies + shows).sortedByDescending { it.modifiedTime }.take(24)
    val recentlyWatched = videos.filter { (state.watch[it.id]?.lastPlayed ?: 0) > 0 }
        .sortedByDescending { state.watch[it.id]?.lastPlayed ?: 0 }.take(24)
    return HomeRows(continuing, recentlyAdded, movies, shows, recentlyWatched)
}

@Composable
private fun HomeHeader(state: TvState, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.size(42.dp))
        Spacer(Modifier.width(14.dp))
        Text("Navelo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(22.dp))
        ConnectionPill(state)
        Spacer(Modifier.weight(1f))
        Button(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Search")
        }
        Spacer(Modifier.width(10.dp))
        Button(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Settings")
        }
    }
    state.message?.let {
        Row(modifier = Modifier.padding(horizontal = 56.dp, vertical = 2.dp)) { StatusMessage(it) }
    }
}

@Composable
private fun ConnectionPill(state: TvState) {
    val online = state.online
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (online) NaveloMint.copy(alpha = .12f) else NaveloAmber.copy(alpha = .14f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) NaveloMint else NaveloAmber))
        Text(
            if (online) (state.serverName.ifBlank { "Media phone ready" }) else "Browsing offline",
            style = MaterialTheme.typography.labelLarge,
            color = if (online) NaveloMint else NaveloAmber,
        )
    }
}

@Composable
private fun MediaRow(
    title: String,
    items: List<MediaItem>,
    state: TvState,
    landscape: Boolean,
    restoreFocusId: String?,
    onCardFocus: (String) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
) {
    val restoreItemIndex = remember(items, title, restoreFocusId) {
        if (restoreFocusId?.startsWith("$title:") == true) {
            val id = restoreFocusId.substringAfter(':')
            items.indexOfFirst { it.id == id }.coerceAtLeast(0)
        } else 0
    }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = restoreItemIndex)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 56.dp),
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items = items, key = { "$title:${it.id}" }) { item ->
                MediaCard(
                    item = item,
                    state = state,
                    landscape = landscape,
                    requestInitialFocus = restoreFocusId == "$title:${item.id}",
                    onFocused = { onCardFocus("$title:${item.id}") },
                    onClick = { onOpenItem(item) },
                )
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    state: TvState,
    landscape: Boolean,
    requestInitialFocus: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "card focus")
    val focusRequester = remember { FocusRequester() }
    val metadata = state.metadata[item.id]
    val imageUrl = if (landscape) metadata?.backdropUrl ?: metadata?.episodeImageUrl ?: metadata?.posterUrl
        else metadata?.posterUrl ?: metadata?.episodeImageUrl
    val progress = state.watch[item.id]?.progressFraction() ?: 0f
    val title = if (item.isEpisode() && landscape) episodeTitle(item, state) else humanTitle(item, state)
    val subtitle = cardSubtitle(item, state)

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .width(if (landscape) 300.dp else if (LocalConfiguration.current.screenHeightDp < 540) 150.dp else 176.dp)
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = listOfNotNull(title, subtitle.takeIf(String::isNotBlank)).joinToString(", ")
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF173A49), Color(0xFF0E2029)),
                    )
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) NaveloMint else Color.White.copy(alpha = .10f),
                    shape = RoundedCornerShape(12.dp),
                )
        ) {
            Icon(
                imageVector = if (item.isEpisode()) Icons.Rounded.LiveTv else Icons.Rounded.Movie,
                contentDescription = null,
                tint = NaveloMint.copy(alpha = .55f),
                modifier = Modifier.size(58.dp).align(Alignment.Center),
            )
            NaveloArtwork(
                item = item,
                artworkReference = imageUrl,
                modifier = Modifier.fillMaxSize(),
            )
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = .58f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(NaveloAmber)
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (focused) Color.White else NaveloMist,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) NaveloMint else NaveloMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MovieDetailScreen(item: MediaItem, state: TvState, onBack: () -> Unit, onPlay: (Boolean) -> Unit) {
    val metadata = state.metadata[item.id]
    val progress = state.watch[item.id]
    val hasProgress = progress?.let { it.positionMs > 10_000 && !it.watched } == true
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { withFrameNanos { }; playFocus.requestFocus() }
    DetailBackdrop(item, metadata?.backdropUrl ?: metadata?.posterUrl) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp),
        ) {
            BackButton(onBack)
            Spacer(Modifier.height(28.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PosterArtwork(item, metadata?.posterUrl, Modifier.width(210.dp))
                Spacer(Modifier.width(34.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(humanTitle(item, state), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    MetadataLine(item, state)
                    metadata?.overview?.takeIf(String::isNotBlank)?.let {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 28.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            color = NaveloMist,
                            modifier = Modifier.widthIn(max = 720.dp),
                        )
                    }
                    metadata?.cast?.take(5)?.takeIf { it.isNotEmpty() }?.let { cast ->
                        Spacer(Modifier.height(14.dp))
                        Text("Cast  ${cast.joinToString("  •  ")}", color = NaveloMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onPlay(false) }, modifier = Modifier.focusRequester(playFocus)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (hasProgress) "Resume ${formatPosition(progress?.positionMs ?: 0)}" else "Play")
                        }
                        if (hasProgress) {
                            Button(onClick = { onPlay(true) }) {
                                Icon(Icons.Rounded.Replay, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Over")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowDetailScreen(
    showKey: String,
    initialEpisodeId: String?,
    state: TvState,
    onBack: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit,
) {
    val episodes = remember(state.items, showKey) {
        state.items.filter { it.type == ItemType.VIDEO && it.isEpisode() && it.showKey() == showKey }
            .sortedWith(compareBy<MediaItem> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }
    if (episodes.isEmpty()) {
        LaunchedEffect(showKey) { onBack() }
        return
    }
    val seasons = remember(episodes) { episodes.map { it.season ?: 1 }.distinct().sorted() }
    val initialEpisode = episodes.firstOrNull { it.id == initialEpisodeId }
    var selectedSeason by rememberSaveable(showKey, initialEpisodeId) {
        mutableStateOf(initialEpisode?.season ?: seasons.first())
    }
    val seasonEpisodes = remember(episodes, selectedSeason) { episodes.filter { (it.season ?: 1) == selectedSeason } }
    val representative = episodes.first()
    val metadata = state.metadata[representative.id]
    val selectedSeasonMetadata = seasonEpisodes.firstOrNull()?.let { state.metadata[it.id] } ?: metadata

    DetailBackdrop(representative, metadata?.backdropUrl ?: metadata?.posterUrl) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 38.dp)) {
            Column(modifier = Modifier.width(if (LocalConfiguration.current.screenWidthDp < 1000) 300.dp else 390.dp).padding(end = 32.dp)) {
                BackButton(onBack)
                Spacer(Modifier.height(34.dp))
                Text(humanTitle(representative, state), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                metadata?.year?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(8.dp)); Text(it, color = NaveloMuted, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(14.dp))
                PosterArtwork(
                    seasonEpisodes.firstOrNull() ?: representative,
                    selectedSeasonMetadata?.seasonPosterUrl ?: metadata?.posterUrl,
                    Modifier.width(84.dp),
                )
                metadata?.overview?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, color = NaveloMist, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp)
                }
                metadata?.cast?.take(3)?.takeIf { it.isNotEmpty() }?.let { cast ->
                    Spacer(Modifier.height(12.dp))
                    Text(cast.joinToString("  •  "), color = NaveloMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(24.dp))
                Text("${episodes.size} episode${if (episodes.size == 1) "" else "s"}", color = NaveloMint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(seasons, key = { it }) { season ->
                        Button(onClick = { selectedSeason = season }) {
                            Text(if (season == selectedSeason) "Season $season  ✓" else "Season $season")
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 42.dp),
                ) {
                    items(seasonEpisodes, key = { it.id }) { episode ->
                        EpisodeCard(
                            episode = episode,
                            state = state,
                            requestInitialFocus = episode.id == (initialEpisode?.takeIf { (it.season ?: 1) == selectedSeason }?.id
                                ?: seasonEpisodes.firstOrNull()?.id),
                            onPlay = { startOver -> onPlay(episode, startOver) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: MediaItem,
    state: TvState,
    requestInitialFocus: Boolean,
    onPlay: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val metadata = state.metadata[episode.id]
    val watch = state.watch[episode.id]
    val progress = watch?.progressFraction() ?: 0f
    LaunchedEffect(requestInitialFocus, episode.id) {
        if (requestInitialFocus) { withFrameNanos { }; focusRequester.requestFocus() }
    }
    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF214555) else Color(0xC9112732))
            .border(if (focused) 2.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.08f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPlay(false) }
            .padding(12.dp)
            .semantics { contentDescription = "${episodeNumber(episode)} ${episodeTitle(episode, state)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.width(if (LocalConfiguration.current.screenWidthDp < 1000) 140.dp else 190.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).background(Color(0xFF173A49))
        ) {
            val image = metadata?.episodeImageUrl ?: metadata?.backdropUrl ?: metadata?.seasonPosterUrl
            Icon(Icons.Rounded.PlayArrow, null, tint = NaveloMint, modifier = Modifier.size(44.dp).align(Alignment.Center))
            NaveloArtwork(episode, image, Modifier.fillMaxSize())
            if (progress > 0f) {
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color.Black.copy(.55f)).align(Alignment.BottomCenter)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(NaveloAmber))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "${episodeNumber(episode)}  ${episodeTitle(episode, state)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Color.White else NaveloMist,
            )
            metadata?.episodeAirDate?.takeIf(String::isNotBlank)?.let {
                Text("Aired $it", color = NaveloMuted, style = MaterialTheme.typography.labelLarge)
            }
            metadata?.episodeOverview?.takeIf(String::isNotBlank)?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = NaveloMuted, style = MaterialTheme.typography.bodyMedium)
            }
            if (watch?.watched == true) Text("Watched", color = NaveloMint, style = MaterialTheme.typography.labelLarge)
            else if (progress > 0f) Text("${(progress * 100).roundToInt()}% watched", color = NaveloAmber, style = MaterialTheme.typography.labelLarge)
        }
        Icon(Icons.Rounded.PlayArrow, "Play episode", tint = if (focused) NaveloMint else NaveloMuted, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun SearchScreen(state: TvState, onBack: () -> Unit, onOpenItem: (MediaItem) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val results = remember(query, state.items, state.metadata) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.length < 2) emptyList() else state.items.asSequence()
            .filter { it.type == ItemType.VIDEO }
            .filter { item ->
                sequenceOf(
                    humanTitle(item, state), episodeTitle(item, state), item.showHint.orEmpty(),
                    state.metadata[item.id]?.overview.orEmpty(), state.metadata[item.id]?.episodeOverview.orEmpty(),
                ).any { needle in it.lowercase(Locale.getDefault()) }
            }
            .distinctBy { if (it.isEpisode()) "episode:${it.id}" else it.id }
            .take(120)
            .toList()
    }
    LaunchedEffect(Unit) { withFrameNanos { }; searchFocus.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BackButton(onBack)
            Text("Search", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        NaveloTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Movies, shows, and episodes",
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
        )
        Spacer(Modifier.height(22.dp))
        when {
            query.isBlank() -> SearchPrompt("Type a title, show, or episode name.")
            query.trim().length < 2 -> SearchPrompt("Type one more letter to search.")
            results.isEmpty() -> SearchPrompt("No matches yet. Try a shorter title.")
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 42.dp),
            ) {
                item { Text("${results.size} result${if (results.size == 1) "" else "s"}", color = NaveloMuted) }
                items(results, key = { it.id }) { item ->
                    SearchResult(item, state, onClick = { onOpenItem(item) })
                }
            }
        }
    }
}

@Composable
private fun SearchResult(item: MediaItem, state: TvState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF214555) else Color(0xC9102732))
            .border(if (focused) 2.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.08f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (item.isEpisode()) Icons.Rounded.LiveTv else Icons.Rounded.Movie, null, tint = NaveloMint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (item.isEpisode()) episodeTitle(item, state) else humanTitle(item, state), fontWeight = FontWeight.SemiBold)
            Text(cardSubtitle(item, state), color = NaveloMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Open", color = if (focused) NaveloMint else NaveloMuted)
    }
}

@Composable
private fun SearchPrompt(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = NaveloMint.copy(.65f), modifier = Modifier.size(58.dp))
        Text(text, color = NaveloMuted, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SettingsScreen(
    state: TvState,
    repository: TvRepository,
    onBack: () -> Unit,
    onFixMatch: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    val settingsBusy = state.activeSettingsAction != null
    val rescanFeedback = state.settingsActionFeedback[SettingsAction.RESCAN_LIBRARY]
    val clearFeedback = state.settingsActionFeedback[SettingsAction.CLEAR_MATCHES]
    val refreshFeedback = state.settingsActionFeedback[SettingsAction.REFRESH_LIBRARY]
    LaunchedEffect(Unit) { withFrameNanos { }; firstAction.requestFocus() }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(modifier = Modifier.width(310.dp).padding(end = 42.dp)) {
            BackButton(onBack)
            Spacer(Modifier.height(28.dp))
            Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(
                "Everyday viewing needs no setup. These controls are here for the person who looks after the library.",
                color = NaveloMuted,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(24.dp))
            ConnectionPill(state)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item {
                SettingsSection("Playback") {
                    SettingAction(
                        title = "Play next episode automatically",
                        description = if (state.settings.autoNext) "On" else "Off",
                        action = if (state.settings.autoNext) "Turn off" else "Turn on",
                        onClick = { repository.updateSettings(state.settings.copy(autoNext = !state.settings.autoNext)) },
                        modifier = Modifier.focusRequester(firstAction),
                    )
                    SettingAction(
                        title = "Show subtitles when available",
                        description = if (state.settings.subtitles) "On" else "Off",
                        action = if (state.settings.subtitles) "Turn off" else "Turn on",
                        onClick = { repository.updateSettings(state.settings.copy(subtitles = !state.settings.subtitles)) },
                    )
                }
            }
            item {
                SettingsSection("Library") {
                    SettingAction(
                        title = "Rescan media",
                        description = "Ask your phone to look for added or removed videos.",
                        action = if (rescanFeedback?.phase == SettingsActionPhase.RUNNING) "Scanning…" else "Rescan",
                        icon = Icons.Rounded.Refresh,
                        enabled = state.online && !settingsBusy,
                        feedback = rescanFeedback,
                        onClick = repository::rescan,
                    )
                    SettingAction(
                        title = "Fix a title or poster",
                        description = when {
                            !state.phoneMetadataCapabilityKnown -> "Reconnect to the phone to check for movie and show information."
                            !state.phoneMetadataSupported -> "Update Navelo on the phone to choose title matches here."
                            !state.settings.tmdbConfigured -> "Movie and show information is unavailable from your phone."
                            else -> "Choose the right match when a title looks wrong."
                        },
                        action = "Fix Match",
                        icon = Icons.Rounded.Edit,
                        enabled = state.online && !settingsBusy && state.phoneMetadataSupported && state.settings.tmdbConfigured,
                        onClick = onFixMatch,
                    )
                    SettingAction(
                        title = "Clear movie and show matches",
                        description = "Remove matched titles and artwork links on the phone for this library and every connected TV. Video thumbnails stay available.",
                        action = if (clearFeedback?.phase == SettingsActionPhase.RUNNING) "Clearing…" else "Clear",
                        icon = Icons.Rounded.DeleteSweep,
                        enabled = state.online && !settingsBusy && state.phoneMetadataSupported,
                        feedback = clearFeedback,
                        onClick = repository::clearMetadata,
                    )
                }
            }
            item {
                SettingsSection("Movie and show information") {
                    Text(
                        when {
                            !state.phoneMetadataCapabilityKnown -> "Reconnect to your phone to check for movie and show information. Cached titles remain available offline."
                            !state.phoneMetadataSupported -> "Update Navelo on the phone to provide movie and show information to this TV."
                            state.settings.tmdbConfigured -> "Your phone provides titles, descriptions, episode details, and artwork to this TV."
                            else -> "Movie and show information is unavailable from your phone. File names and video thumbnails still work."
                        },
                        color = NaveloMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                SettingsSection("Media phone") {
                    SettingAction(
                        title = "Refresh library",
                        description = "Reload the video list from your phone.",
                        action = if (refreshFeedback?.phase == SettingsActionPhase.RUNNING) "Refreshing…" else "Refresh",
                        icon = Icons.Rounded.Refresh,
                        enabled = state.online && !settingsBusy,
                        feedback = refreshFeedback,
                        onClick = repository::refreshLibrary,
                    )
                    SettingAction(
                        title = "Switch media phone",
                        description = "Forget this connection and choose another Navelo phone.",
                        action = "Switch",
                        icon = Icons.Rounded.SwapHoriz,
                        onClick = repository::switchServer,
                    )
                }
            }
            item {
                SettingsSection("About") {
                    Text("Navelo sends video directly from your phone over shared Wi-Fi or the phone’s hotspot. Your phone looks up title and artwork information and shares a local copy with this TV.", color = NaveloMist)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Image(
                            painter = painterResource(R.drawable.tmdb_logo),
                            contentDescription = "The Movie Database",
                            modifier = Modifier.width(94.dp).aspectRatio(185.04f / 133.4f),
                        )
                        Text(
                            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                            color = NaveloMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FixMatchScreen(state: TvState, repository: TvRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.items.firstOrNull { it.id == selectedId }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable(selectedId) { mutableStateOf(selected?.let { humanTitle(it, state) }.orEmpty()) }
    var results by remember(selectedId) { mutableStateOf<List<Metadata>>(emptyList()) }
    var searching by remember(selectedId) { mutableStateOf(false) }
    var searchMessage by remember(selectedId) { mutableStateOf<String?>(null) }
    val matchingItems = remember(libraryQuery, state.items, state.metadata) {
        val needle = libraryQuery.trim().lowercase(Locale.getDefault())
        state.items.asSequence()
            .filter { it.type == ItemType.VIDEO }
            .filter { item ->
                needle.isBlank() || needle in item.filename.lowercase(Locale.getDefault()) ||
                    needle in humanTitle(item, state).lowercase(Locale.getDefault())
            }
            .take(250)
            .toList()
    }

    BackHandler {
        if (selected != null) selectedId = null else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BackButton { if (selected != null) selectedId = null else onBack() }
            Text(if (selected == null) "Fix Match" else "Choose the right match", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (selected == null) "Owner tool · Raw filenames are shown here only to help identify a video."
            else selected.filename,
            color = NaveloMuted,
        )
        Spacer(Modifier.height(20.dp))
        if (selected == null) {
            NaveloTextField(
                value = libraryQuery,
                onValueChange = { libraryQuery = it },
                placeholder = "Find a movie, show, or filename",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(matchingItems, key = { it.id }) { item ->
                    MatchItemRow(item, state, onClick = { selectedId = item.id })
                }
            }
        } else {
            NaveloTextField(query, { query = it }, "Search title and optional year", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = query.isNotBlank() && !searching && state.online && state.phoneMetadataSupported && state.settings.tmdbConfigured,
                onClick = {
                    searching = true
                    searchMessage = null
                    scope.launch {
                        results = runCatching { repository.searchMetadata(selected, query.trim()) }
                            .onFailure { searchMessage = "We couldn’t search for matches. Check the phone and try again." }
                            .getOrDefault(emptyList())
                        if (results.isEmpty() && searchMessage == null) searchMessage = "No close matches found. Try a shorter title."
                        searching = false
                    }
                },
            ) {
                Icon(Icons.Rounded.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (searching) "Searching…" else "Find matches")
            }
            if (!state.online) {
                Spacer(Modifier.height(10.dp))
                StatusMessage("Reconnect to the phone to search for title matches.")
            } else if (!state.phoneMetadataSupported) {
                Spacer(Modifier.height(10.dp))
                StatusMessage("Update Navelo on the phone to search for title matches from this TV.")
            } else if (!state.settings.tmdbConfigured) {
                Spacer(Modifier.height(10.dp))
                StatusMessage("Movie and show information is unavailable from your phone. File names and video thumbnails still work.")
            }
            searchMessage?.let { Spacer(Modifier.height(10.dp)); StatusMessage(it) }
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results, key = { it.id }) { match ->
                    MetadataChoice(selected, match) {
                        repository.fixMatch(selected, match)
                        selectedId = null
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchItemRow(item: MediaItem, state: TvState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF214555) else Color(0xC9102732))
            .border(if (focused) 2.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.08f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (item.isEpisode()) Icons.Rounded.LiveTv else Icons.Rounded.Movie, null, tint = NaveloMint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (item.isEpisode()) episodeTitle(item, state) else humanTitle(item, state), fontWeight = FontWeight.SemiBold)
            Text(item.filename, color = NaveloMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (state.metadata.containsKey(item.id)) "Matched" else "Choose", color = if (focused) NaveloMint else NaveloMuted)
    }
}

@Composable
private fun MetadataChoice(item: MediaItem, metadata: Metadata, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = Modifier.width(190.dp)
            .scale(animateFloatAsState(if (focused) 1.06f else 1f, label = "match focus").value)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Use ${metadata.title} ${metadata.year}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF173A49))
                .border(if (focused) 3.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.1f), RoundedCornerShape(12.dp))
        ) {
            NaveloArtwork(item, metadata.posterUrl, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Text(metadata.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(metadata.year, color = if (focused) NaveloMint else NaveloMuted)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xC5102732)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NaveloMint)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun SettingAction(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    feedback: SettingsActionFeedback? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier.fillMaxWidth().alpha(if (enabled || feedback?.phase == SettingsActionPhase.RUNNING) 1f else .5f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF214555) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.06f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (focused) NaveloMint else NaveloMuted)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = NaveloMuted, style = MaterialTheme.typography.bodySmall)
            feedback?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it.message,
                    color = when (it.phase) {
                        SettingsActionPhase.RUNNING, SettingsActionPhase.SUCCESS -> NaveloMint
                        SettingsActionPhase.ERROR -> Color(0xFFFFB4AB)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(action, color = if (focused) NaveloMint else NaveloMuted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NaveloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xE8122D39))
            .border(if (focused) 3.dp else 1.dp, if (focused) NaveloMint else Color.White.copy(.14f), RoundedCornerShape(13.dp))
            .padding(horizontal = 18.dp, vertical = 15.dp),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = NaveloMist),
        singleLine = true,
        cursorBrush = SolidColor(NaveloMint),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = NaveloMuted, style = MaterialTheme.typography.titleMedium)
                inner()
            }
        },
    )
}

@Composable
private fun PosterArtwork(item: MediaItem, imageUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF173A49))
            .border(1.dp, Color.White.copy(.12f), RoundedCornerShape(14.dp)),
    ) {
        Icon(
            Icons.Rounded.Movie,
            contentDescription = null,
            tint = NaveloMint.copy(alpha = .55f),
            modifier = Modifier.size(48.dp).align(Alignment.Center),
        )
        NaveloArtwork(item, imageUrl, Modifier.fillMaxSize())
    }
}

@Composable
private fun DetailBackdrop(item: MediaItem, imageUrl: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        NaveloArtwork(
            item = item,
            artworkReference = imageUrl,
            modifier = Modifier.fillMaxSize().alpha(.48f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(NaveloNavy, NaveloNavy.copy(alpha = .90f), NaveloNavy.copy(alpha = .36f)),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, NaveloNavy.copy(.35f), NaveloNavy)),
            )
        )
        content()
    }
}

@Composable
private fun MetadataLine(item: MediaItem, state: TvState) {
    val metadata = state.metadata[item.id]
    val pieces = buildList {
        (metadata?.year?.takeIf(String::isNotBlank) ?: item.year?.toString())?.let(::add)
        metadata?.runtimeMinutes?.takeIf { it > 0 }?.let { add(formatRuntime(it)) }
        metadata?.genres?.take(2)?.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let(::add)
        metadata?.rating?.takeIf { it > 0 }?.let { add("★ %.1f".format(Locale.US, it)) }
    }
    Text(pieces.joinToString("   •   "), style = MaterialTheme.typography.titleMedium, color = NaveloMint)
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
        Spacer(Modifier.width(7.dp))
        Text("Back")
    }
}

@Composable
private fun OfflinePlayPanel(onRetry: () -> Unit, onDismiss: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; retryFocus.requestFocus() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(.56f)), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier.padding(32.dp).widthIn(max = 760.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF18323E))
                    .border(1.dp, NaveloAmber.copy(.5f), RoundedCornerShape(18.dp)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(Icons.Rounded.Tv, null, tint = NaveloAmber, modifier = Modifier.size(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Media phone unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Connect both devices to the same Wi-Fi, or connect the TV to your phone’s hotspot.", color = NaveloMuted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Try Again") }
                    Button(onClick = onDismiss) { Text("Keep Browsing") }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(online: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(Modifier.size(92.dp))
        Spacer(Modifier.height(20.dp))
        Text(if (online) "Your library is getting ready" else "Your saved library is empty", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (online) "New movies and shows will appear here automatically."
            else "Reconnect to your media phone to load it for the first time.",
            color = NaveloMuted,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRefresh) { Text("Open Settings") }
    }
}

@Composable
private fun StatusMessage(message: String) {
    Text(
        message,
        color = NaveloAmber,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(NaveloAmber.copy(.10f)).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(27)).background(
            Brush.linearGradient(listOf(NaveloMint, Color(0xFF3AB5A0))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Navelo", tint = NaveloNavy, modifier = Modifier.fillMaxSize(.62f))
        Box(
            Modifier.fillMaxWidth(.48f).height(4.dp).align(Alignment.BottomCenter).padding(bottom = 1.dp)
                .background(NaveloAmber, CircleShape)
        )
    }
}

private fun MediaItem.isEpisode(): Boolean = season != null || episode != null || !showHint.isNullOrBlank()

private fun MediaItem.showKey(): String = (showHint ?: titleHint ?: relativePath.substringBefore('/'))
    .trim().lowercase(Locale.ROOT)

private fun humanTitle(item: MediaItem, state: TvState): String {
    val metadata = state.metadata[item.id]
    val preferred = if (item.isEpisode()) metadata?.title ?: item.showHint ?: item.titleHint else metadata?.title ?: item.titleHint
    return preferred?.takeIf(String::isNotBlank) ?: cleanFilename(item.displayName.ifBlank { item.filename })
}

private fun episodeTitle(item: MediaItem, state: TvState): String =
    state.metadata[item.id]?.episodeTitle?.takeIf(String::isNotBlank)
        ?: "Episode ${item.episode ?: 1}"

private fun episodeNumber(item: MediaItem): String = "E${item.episode ?: 1}"

private fun cardSubtitle(item: MediaItem, state: TvState): String {
    val metadata = state.metadata[item.id]
    return when {
        item.isEpisode() -> "${humanTitle(item, state)} · Season ${item.season ?: 1}, Episode ${item.episode ?: 1}"
        !metadata?.year.isNullOrBlank() -> metadata?.year.orEmpty()
        item.year != null -> item.year.toString()
        else -> "Movie"
    }
}

private fun cleanFilename(value: String): String = value.substringBeforeLast('.')
    .replace(Regex("[._]+"), " ")
    .replace(Regex("(?i)\\b(2160p|1080p|720p|web[ .-]?dl|bluray|x26[45]|hevc|aac|dts|remux).*$"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun WatchProgress.progressFraction(): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

private fun formatPosition(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}" else "$minutes min"
}

private fun formatRuntime(minutes: Int): String = if (minutes >= 60) {
    "${minutes / 60}h ${minutes % 60}m"
} else "$minutes min"

@Composable
private fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
                 tint: Color = androidx.tv.material3.LocalContentColor.current) {
    MaterialIcon(imageVector, contentDescription, modifier, tint)
}
