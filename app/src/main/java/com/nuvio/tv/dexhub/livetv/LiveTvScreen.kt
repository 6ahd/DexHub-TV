@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Live TV page's route; it has a menu entry of its own (MainActivity) next to Library. */
const val LiveTvRoute = "dexhub_live_tv"

/** What a pick plays: [contentType] "channel" for a live channel (the player then treats it as live), "movie" else. */
data class LivePlay(
    val url: String,
    val title: String,
    val headers: Map<String, String>,
    val poster: String?,
    val contentType: String,
    val sourceName: String
)

/**
 * Dex Hub TV's Live TV (DEXHUB.md), from Flix's live guide: the playlists along the top, then a tab each for its
 * channels, films and series. Channels: the groups (favourites first), the channels with the programme on now, and
 * a preview of the focused channel that changes as the focus moves; holding OK adds a channel to the favourites.
 * Films and series: the groups and a grid of posters; a series opens on its episodes. A pick plays through [onPlay].
 */
@Composable
fun LiveTvScreen(onPlay: (LivePlay) -> Unit, viewModel: LiveTvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    BackHandler(enabled = state.series != null) { viewModel.closeSeries() }
    val glow = NuvioTheme.colors.Secondary
    Column(
        Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.12f, 0f), radius = size.width * 0.6f))
            }
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 20.dp)
    ) {
        Header(state, viewModel)
        if (!state.adding && state.tabs.size > 1) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.tabs.forEach { tab ->
                    Pill(stringResource(tab.label()), selected = tab == state.tab) { viewModel.selectTab(tab) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val source = state.source
        when {
            state.adding -> AddPanel(
                onM3u = viewModel::addM3u,
                onXtream = viewModel::addXtream,
                onCancel = { viewModel.showAdd(false) }.takeIf { state.sources.isNotEmpty() }
            )
            state.loading -> Message(stringResource(R.string.dexhub_live_loading))
            state.error != null -> Message(state.error.orEmpty())
            state.lineup != null && source != null -> AnimatedContent(
                targetState = state.tab,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                label = "liveTab"
            ) { tab ->
                when (tab) {
                    LiveTab.LIVE -> Channels(state, now, viewModel) { channel ->
                        onPlay(LivePlay(channel.url, channel.name, channel.headers, channel.logo, "channel", source.name))
                    }
                    LiveTab.MOVIES -> Posters(
                        state, viewModel::selectGroup, state.movies.map { Tile(it.id, it.name, it.poster, it.year) }
                    ) { id ->
                        state.movies.firstOrNull { it.id == id }?.let { m ->
                            onPlay(LivePlay(m.url, m.name, m.headers, m.poster, "movie", source.name))
                        }
                    }
                    LiveTab.SERIES -> {
                        val series = state.series
                        if (series != null) {
                            Episodes(series, state.episodes) { e ->
                                onPlay(LivePlay(e.url, "${series.name} · S${e.season}E${e.episode}", e.headers,
                                    e.still ?: series.poster, "movie", source.name))
                            }
                        } else {
                            Posters(state, viewModel::selectGroup, state.shows.map { Tile(it.id, it.name, it.poster, null) }) { id ->
                                state.shows.firstOrNull { it.id == id }?.let(viewModel::openSeries)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LiveTab.label(): Int = when (this) {
    LiveTab.LIVE -> R.string.dexhub_live_tab_live
    LiveTab.MOVIES -> R.string.dexhub_live_tab_movies
    LiveTab.SERIES -> R.string.dexhub_live_tab_series
}

@Composable
private fun Header(state: LiveTvUiState, viewModel: LiveTvViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.dexhub_nav_live_tv),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(Modifier.width(28.dp))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.sources, key = { it.id }) { source ->
                Pill(source.name, selected = source.id == state.source?.id && !state.adding) {
                    viewModel.showAdd(false)
                    viewModel.select(source)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (state.source != null && !state.adding) {
            Pill(stringResource(R.string.dexhub_live_refresh), selected = false) { viewModel.refresh() }
            Spacer(Modifier.width(10.dp))
            Pill(stringResource(R.string.dexhub_live_remove), selected = false) { state.source?.let(viewModel::remove) }
            Spacer(Modifier.width(10.dp))
        }
        Pill(stringResource(R.string.dexhub_live_add), selected = state.adding) { viewModel.showAdd(true) }
    }
}

@Composable
private fun Groups(state: LiveTvUiState, onGroup: (String) -> Unit, first: FocusRequester) {
    val favourites = stringResource(R.string.dexhub_live_favourites)
    LazyColumn(
        Modifier.width(250.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        items(state.groups, key = { it }) { group ->
            Pill(
                if (group == LiveTvUiState.FavouritesGroup) "★  $favourites" else group,
                selected = group == state.group, wide = true,
                modifier = Modifier.animateItem().then(
                    if (group == state.groups.firstOrNull()) Modifier.focusRequester(first) else Modifier
                )
            ) { onGroup(group) }
        }
    }
}

@Composable
private fun Channels(state: LiveTvUiState, now: Long, viewModel: LiveTvViewModel, onPlay: (LiveChannel) -> Unit) {
    val first = remember(state.lineup) { FocusRequester() }
    LaunchedEffect(state.lineup) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        Groups(state, viewModel::selectGroup, first)
        Spacer(Modifier.width(22.dp))
        val channels = state.channels
        if (channels.isEmpty()) {
            Box(Modifier.weight(1f)) { Message(stringResource(R.string.dexhub_live_no_channels)) }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(channels, key = { it.id }) { channel ->
                    val programs = remember(channel.id, state.guide, now / 60_000) {
                        state.guide?.upcoming(channel.epgId, channel.name, now).orEmpty()
                    }
                    ChannelRow(
                        channel, programs, now, favourite = channel.id in state.favourites,
                        modifier = Modifier.animateItem(),
                        onFocused = { viewModel.focus(channel) },
                        onLongClick = { viewModel.toggleFavourite(channel) }
                    ) { onPlay(channel) }
                }
            }
        }
        Spacer(Modifier.width(22.dp))
        Preview(state.focused ?: channels.firstOrNull(), state, now)
    }
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun span(p: LiveProgram) = clock.format(Date(p.startMs)) + " – " + clock.format(Date(p.endMs))

/** The focused channel, large: its logo on the theme's colour, then the programme on now with its times and the next. */
@Composable
private fun Preview(channel: LiveChannel?, state: LiveTvUiState, now: Long) {
    AnimatedContent(
        targetState = channel,
        transitionSpec = {
            (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 14 }) togetherWith fadeOut(tween(140))
        },
        contentKey = { it?.id },
        label = "livePreview",
        modifier = Modifier.width(380.dp).fillMaxHeight()
    ) { shown ->
        if (shown == null) return@AnimatedContent
        val programs = remember(shown.id, state.guide, now / 60_000) {
            state.guide?.upcoming(shown.epgId, shown.name, now, limit = 3).orEmpty()
        }
        val accent = NuvioTheme.colors.Secondary
        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), NuvioTheme.colors.BackgroundCard))),
                contentAlignment = Alignment.Center
            ) {
                if (shown.logo != null) {
                    AsyncImage(shown.logo, contentDescription = shown.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(36.dp))
                } else {
                    Text(shown.name, color = Color.White, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                listOfNotNull(shown.number?.toString(), shown.name).joinToString("  ·  "),
                color = NuvioTheme.colors.TextPrimary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val on = programs.firstOrNull()?.takeIf { it.startMs <= now }
            if (on != null) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.dexhub_live_now) + "  ·  " + span(on), color = accent, fontSize = 14.sp)
                Text(on.title, color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val done = ((now - on.startMs).toFloat() / (on.endMs - on.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.14f))) {
                    Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(accent))
                }
            }
            programs.filter { it.startMs > now }.take(2).forEach { next ->
                Spacer(Modifier.height(12.dp))
                Text(span(next), color = NuvioTheme.colors.TextTertiary, fontSize = 13.sp)
                Text(next.title, color = NuvioTheme.colors.TextSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.dexhub_live_hold_hint), color = NuvioTheme.colors.TextTertiary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    programs: List<LiveProgram>,
    now: Long,
    favourite: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(84.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.85f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo != null) {
                    AsyncImage(channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp))
                } else {
                    Text(channel.name.take(2).uppercase(), color = NuvioTheme.colors.TextSecondary, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            channel.number?.let {
                Text("$it", color = NuvioTheme.colors.TextSecondary, fontSize = 17.sp, modifier = Modifier.width(46.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    (if (favourite) "★ " else "") + channel.name, color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val on = programs.firstOrNull()?.takeIf { it.startMs <= now }
                if (on != null) {
                    Text(on.title, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    val done = ((now - on.startMs).toFloat() / (on.endMs - on.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.14f))) {
                        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(NuvioTheme.colors.Secondary))
                    }
                }
            }
        }
    }
}

/** A poster in the films' or series' grid. */
private data class Tile(val id: String, val title: String, val poster: String?, val year: String?)

@Composable
private fun Posters(state: LiveTvUiState, onGroup: (String) -> Unit, tiles: List<Tile>, onOpen: (String) -> Unit) {
    val first = remember(state.lineup, state.tab) { FocusRequester() }
    LaunchedEffect(state.lineup, state.tab) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        Groups(state, onGroup, first)
        Spacer(Modifier.width(22.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 30.dp)
        ) {
            items(tiles, key = { it.id }) { tile ->
                Column(Modifier.animateItem()) {
                    val shape = RoundedCornerShape(12.dp)
                    Card(
                        onClick = { onOpen(tile.id) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        shape = CardDefaults.shape(shape),
                        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
                        scale = CardDefaults.scale(focusedScale = 1.07f)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (tile.poster != null) {
                                AsyncImage(tile.poster, contentDescription = tile.title, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                            } else {
                                Text(tile.title, color = NuvioTheme.colors.TextSecondary, fontSize = 15.sp,
                                    modifier = Modifier.align(Alignment.Center).padding(10.dp))
                            }
                        }
                    }
                    Text(
                        listOfNotNull(tile.title, tile.year).joinToString(" · "), color = NuvioTheme.colors.TextSecondary,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** A series' episodes by season; Back returns to the series. */
@Composable
private fun Episodes(series: LiveSeries, episodes: List<LiveEpisode>?, onPlay: (LiveEpisode) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(250.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(NuvioTheme.colors.BackgroundCard)) {
                series.poster?.let {
                    AsyncImage(it, contentDescription = series.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(series.name, color = NuvioTheme.colors.TextPrimary, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(26.dp))
        when {
            episodes == null -> Box(Modifier.weight(1f)) { Message(stringResource(R.string.dexhub_live_loading)) }
            episodes.isEmpty() -> Box(Modifier.weight(1f)) { Message(stringResource(R.string.dexhub_live_no_episodes)) }
            else -> {
                val first = remember(episodes) { FocusRequester() }
                LaunchedEffect(episodes) { runCatching { first.requestFocus() } }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(episodes, key = { it.id }) { e ->
                        val shape = RoundedCornerShape(12.dp)
                        Card(
                            onClick = { onPlay(e) },
                            modifier = Modifier.animateItem().fillMaxWidth().height(64.dp)
                                .then(if (e == episodes.first()) Modifier.focusRequester(first) else Modifier),
                            shape = CardDefaults.shape(shape),
                            colors = CardDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("S${e.season} · E${e.episode}", color = NuvioTheme.colors.Secondary, fontSize = 15.sp,
                                    modifier = Modifier.width(110.dp))
                                Text(e.title, color = NuvioTheme.colors.TextPrimary, fontSize = 17.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPanel(
    onM3u: (String, String, String) -> Unit,
    onXtream: (String, String, String, String) -> Unit,
    onCancel: (() -> Unit)?
) {
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var guide by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dexhub_live_empty), color = NuvioTheme.colors.TextSecondary, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.dexhub_live_m3u), selected = !xtream) { xtream = false }
            Pill(stringResource(R.string.dexhub_live_xtream), selected = xtream) { xtream = true }
        }
        Field(stringResource(R.string.dexhub_live_name), name) { name = it }
        if (xtream) {
            Field(stringResource(R.string.dexhub_live_server), url, KeyboardType.Uri) { url = it }
            Field(stringResource(R.string.dexhub_live_username), user) { user = it }
            Field(stringResource(R.string.dexhub_live_password), password, KeyboardType.Password, secret = true) { password = it }
        } else {
            Field(stringResource(R.string.dexhub_live_url), url, KeyboardType.Uri) { url = it }
            Field(stringResource(R.string.dexhub_live_guide), guide, KeyboardType.Uri) { guide = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.dexhub_live_save), selected = true) {
                if (xtream) onXtream(name, url, user, password) else onM3u(name, url, guide)
            }
            onCancel?.let { Pill(stringResource(R.string.dexhub_live_cancel), selected = false, onClick = it) }
        }
    }
}

/** A field on a TV: OK on it starts typing, Done (or leaving it) stops, so the remote never gets stuck in it. */
@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val keys = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { input.requestFocus() }
            keys?.show()
        }
    }
    Card(
        onClick = { editing = true },
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Field,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.01f)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
            Text(label, color = NuvioTheme.colors.TextTertiary, fontSize = 13.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(NuvioTheme.colors.Secondary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    keys?.hide()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(input)
                    .focusProperties { canFocus = editing }
            )
        }
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Card(
        onClick = onClick,
        modifier = modifier.then(if (wide) Modifier.fillMaxWidth().height(48.dp) else Modifier.height(44.dp)),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.28f) else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary
        ),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(
            Modifier.fillMaxHeight().padding(horizontal = 20.dp),
            contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center
        ) {
            Text(label, color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = NuvioTheme.colors.TextSecondary, fontSize = 18.sp)
    }
}
