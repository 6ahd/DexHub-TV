@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.dexworld

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** The DexWorld page's route; it has a menu entry of its own (MainActivity) after Live TV. */
const val DexWorldRoute = "dexhub_dexworld"

data class DexWorldUiState(
    val shelves: List<DexWorldClient.Shelf> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val section: DexWorldClient.Member? = null,
    val items: List<DexWorldClient.Item>? = null
)

@HiltViewModel
class DexWorldViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val client = DexWorldClient(File(context.filesDir, "dexhub_dexworld"))
    private val language get() = Locale.getDefault().language
    private val _state = MutableStateFlow(DexWorldUiState())
    val state: StateFlow<DexWorldUiState> = _state
    private var opening: Job? = null

    init {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { client.page(language) }
            _state.update {
                it.copy(shelves = rows?.let(DexWorldClient::shelves).orEmpty(), loading = false, failed = rows == null)
            }
        }
    }

    fun open(section: DexWorldClient.Member) {
        opening?.cancel()
        _state.update { it.copy(section = section, items = null) }
        opening = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { client.items(section.id, language) }
            _state.update { if (it.section?.id == section.id) it.copy(items = items.orEmpty().distinctBy { item -> item.contentId }) else it }
        }
    }

    fun close() {
        opening?.cancel()
        _state.update { it.copy(section = null, items = null) }
    }
}

/**
 * Dex Hub TV's DexWorld page (DEXHUB.md), from Flix: DexWorld's ready sections on shelves, each with the site's own
 * cover; a section opens as a grid of its titles, and a title opens its page through [onOpenTitle].
 */
@Composable
fun DexWorldScreen(onOpenTitle: (id: String, type: String) -> Unit, viewModel: DexWorldViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val section = state.section
    // the picture of what has the focus, behind the page, fading from one to the next
    var backdrop by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = section != null) { viewModel.close() }
    val background = NuvioTheme.colors.Background
    Box(Modifier.fillMaxSize().background(background)) {
        Crossfade(targetState = backdrop, animationSpec = tween(700), label = "dexworldBackdrop") { picture ->
            if (picture != null) {
                AsyncImage(picture, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.42f))
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(background, background.copy(alpha = 0.55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(alpha = 0.85f), background))))
        when {
            section != null -> Section(section, state.items, onOpenTitle) { backdrop = it }
            state.loading -> Message(stringResource(R.string.dexhub_dexworld_loading))
            state.shelves.isEmpty() -> Message(stringResource(R.string.dexhub_dexworld_failed))
            else -> Shelves(state.shelves, viewModel::open) { backdrop = it }
        }
    }
}

@Composable
private fun Shelves(
    shelves: List<DexWorldClient.Shelf>,
    onOpen: (DexWorldClient.Member) -> Unit,
    onPicture: (String?) -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(shelves) { runCatching { first.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        item {
            Text(
                "DexWorld", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        }
        items(shelves, key = { it.id }) { shelf ->
            Column(Modifier.animateItem()) {
                Text(
                    shelf.title, color = NuvioTheme.colors.TextPrimary, fontSize = 20.sp,
                    modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 10.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(shelf.sections, key = { it.id }) { member ->
                        val isFirst = shelf == shelves.first() && member == shelf.sections.first()
                        SectionCard(
                            member,
                            (if (isFirst) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.isFocused) onPicture(member.cover) }
                        ) { onOpen(member) }
                    }
                }
            }
        }
    }
}

/** A section as the site draws it: its cover carries its name; without one, its name over the theme's card. */
@Composable
private fun SectionCard(member: DexWorldClient.Member, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        onClick = onClick,
        modifier = modifier.width(300.dp).aspectRatio(16f / 9f),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.06f)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (member.cover != null) {
                AsyncImage(member.cover, contentDescription = member.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            }
            if (member.cover == null || "blocks.dexworld.cc" !in member.cover) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                Text(
                    member.title, color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun Section(
    section: DexWorldClient.Member,
    items: List<DexWorldClient.Item>?,
    onOpenTitle: (String, String) -> Unit,
    onPicture: (String?) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp)) {
        Text(section.title, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
        Spacer(Modifier.height(18.dp))
        when {
            items == null -> Message(stringResource(R.string.dexhub_dexworld_loading))
            items.isEmpty() -> Message(stringResource(R.string.dexhub_dexworld_failed))
            else -> {
                val first = remember(items) { FocusRequester() }
                LaunchedEffect(items) { runCatching { first.requestFocus() } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(bottom = 36.dp, top = 6.dp)
                ) {
                    items(items, key = { it.contentId!! }) { item ->
                        Poster(
                            item,
                            Modifier.animateItem()
                                .then(if (item == items.first()) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.hasFocus) onPicture(item.backdrop ?: item.poster) }
                        ) {
                            item.contentId?.let { onOpenTitle(it, item.contentType) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: DexWorldClient.Item, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
            border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.06f)
        ) {
            Box(Modifier.fillMaxSize()) {
                if (item.poster != null) {
                    AsyncImage(item.poster, contentDescription = item.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                } else {
                    Text(item.title, color = NuvioTheme.colors.TextSecondary, fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp))
                }
            }
        }
        Text(
            listOfNotNull(item.title, item.year).joinToString(" · "), color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = NuvioTheme.colors.TextSecondary, fontSize = 18.sp)
    }
}
