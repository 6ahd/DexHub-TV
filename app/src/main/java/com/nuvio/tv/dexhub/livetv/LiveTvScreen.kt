@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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

/** The Live TV page's route; it has a menu entry of its own (MainActivity) next to Library. */
const val LiveTvRoute = "dexhub_live_tv"

/**
 * Dex Hub TV's Live TV (DEXHUB.md), from Flix's live guide: the playlists along the top, a playlist's groups on the
 * left and their channels on the right, each with its logo, the programme on now and the next one. OK on a channel
 * plays it in the player; [onPlay] opens it.
 */
@Composable
fun LiveTvScreen(onPlay: (LiveChannel, LiveSource) -> Unit, viewModel: LiveTvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 20.dp)
    ) {
        Header(state, viewModel)
        Spacer(Modifier.height(18.dp))
        when {
            state.adding -> AddPanel(
                onM3u = viewModel::addM3u,
                onXtream = viewModel::addXtream,
                onCancel = { viewModel.showAdd(false) }.takeIf { state.sources.isNotEmpty() }
            )
            state.loading -> Message(stringResource(R.string.dexhub_live_loading))
            state.error != null -> Message(state.error.orEmpty())
            state.lineup != null -> Lineup(state, now, viewModel::selectGroup) { channel ->
                state.source?.let { onPlay(channel, it) }
            }
        }
    }
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
private fun Lineup(state: LiveTvUiState, now: Long, onGroup: (String) -> Unit, onPlay: (LiveChannel) -> Unit) {
    val lineup = state.lineup ?: return
    val first = remember(lineup) { FocusRequester() }
    LaunchedEffect(lineup) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(280.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(lineup.groups, key = { it }) { group ->
                Pill(
                    group, selected = group == state.group, wide = true,
                    modifier = if (group == lineup.groups.firstOrNull()) Modifier.focusRequester(first) else Modifier
                ) { onGroup(group) }
            }
        }
        Spacer(Modifier.width(24.dp))
        val channels = state.channels
        if (channels.isEmpty()) {
            Message(stringResource(R.string.dexhub_live_no_channels))
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
                    ChannelRow(channel, programs, now) { onPlay(channel) }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: LiveChannel, programs: List<LiveProgram>, now: Long, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(92.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 104.dp, height = 60.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Text(channel.name.take(2).uppercase(), color = NuvioTheme.colors.TextSecondary, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            channel.number?.let {
                Text("$it", color = NuvioTheme.colors.TextSecondary, fontSize = 18.sp, modifier = Modifier.width(52.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name, color = NuvioTheme.colors.TextPrimary, fontSize = 19.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val on = programs.firstOrNull()?.takeIf { it.startMs <= now }
                if (on != null) {
                    Text(
                        on.title, color = NuvioTheme.colors.TextSecondary, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val done = ((now - on.startMs).toFloat() / (on.endMs - on.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.14f))) {
                        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(NuvioTheme.colors.Secondary))
                    }
                }
                val next = programs.firstOrNull { it.startMs > now }
                if (next != null) {
                    Text(
                        stringResource(R.string.dexhub_live_next, next.title), color = NuvioTheme.colors.TextTertiary,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
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
