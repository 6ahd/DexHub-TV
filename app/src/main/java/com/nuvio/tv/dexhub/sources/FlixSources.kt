@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.sources

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.WorkspacePremium
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.dexhub.ui.DexHubCard
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dex Hub TV's sources in Flix's design (DEXHUB.md; Flix/App/FlixSourcesRail.swift): the qualities as tabs, an order
 * to sort by, and a card for each add-on at the chosen quality that names how many streams it has, with its format
 * (the quality, HDR or Dolby Vision, the audio), its size and whether it is cached; the best stream's card wears a
 * crown. A card with one stream plays it; a card with more opens the add-on's list. Chosen in Layout > Sources;
 * Arc's own list stays the other choice.
 */
object DexHubSourcesStyle {
    private const val PREFS = "dexhub_look"
    private const val KEY = "sources_flix"
    private val state = MutableStateFlow(true)
    @Volatile private var loaded = false

    fun flix(context: Context): StateFlow<Boolean> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = prefs(context).getBoolean(KEY, true)
                loaded = true
            }
        }
        return state
    }

    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        loaded = true
        state.value = on
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Whether the sources page draws Flix's design; follows a change at once. */
@Composable
fun rememberFlixSources(): Boolean {
    val context = LocalContext.current
    val on by remember(context) { DexHubSourcesStyle.flix(context) }.collectAsState()
    return on
}

/** What a stream's texts say about it, as Flix's cards show it. */
internal data class StreamFacts(
    val quality: String,
    val rank: Int,
    val range: String?,
    val audio: String?,
    val sizeBytes: Long?,
    val cached: Boolean
) {
    val size: String?
        get() = sizeBytes?.let { if (it >= 1L shl 30) "%.1f GB".format(it / 1073741824.0) else "${it shr 20} MB" }

    companion object {
        private val qualities = listOf(
            Regex("""(?i)\b(2160p|4k|uhd)\b""") to "4K",
            Regex("""(?i)\b1440p\b""") to "1440p",
            Regex("""(?i)\b(1080p|fhd)\b""") to "1080p",
            Regex("""(?i)\b720p\b""") to "720p",
            Regex("""(?i)\b(576p|480p|360p|sd)\b""") to "SD"
        )
        private val sizePattern = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)\b""")

        fun of(stream: Stream): StreamFacts {
            val text = listOfNotNull(stream.name, stream.title, stream.description, stream.quality).joinToString(" ")
            val quality = qualities.firstOrNull { it.first.containsMatchIn(text) }?.second ?: Other
            val range = when {
                Regex("""(?i)\b(dv|dovi|dolby ?vision)\b""").containsMatchIn(text) -> "Dolby Vision"
                Regex("""(?i)\bhdr10\+|hdr10plus\b""").containsMatchIn(text) -> "HDR10+"
                Regex("""(?i)\bhdr(10)?\b""").containsMatchIn(text) -> "HDR"
                else -> null
            }
            val codec = when {
                Regex("""(?i)\batmos\b""").containsMatchIn(text) -> "Atmos"
                Regex("""(?i)\btruehd\b""").containsMatchIn(text) -> "TrueHD"
                Regex("""(?i)\bdts[- ]?(hd|x|ma)\b""").containsMatchIn(text) -> "DTS-HD"
                Regex("""(?i)\bdts\b""").containsMatchIn(text) -> "DTS"
                Regex("""(?i)\b(ddp|dd\+|eac3|e-ac-3)\b""").containsMatchIn(text) -> "DD+"
                Regex("""(?i)\b(dd|ac3)\b""").containsMatchIn(text) -> "DD"
                Regex("""(?i)\baac\b""").containsMatchIn(text) -> "AAC"
                else -> null
            }
            val channels = Regex("""\b([2578])[ .]([01])\b""").find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            val size = stream.behaviorHints?.videoSize?.takeIf { it > 0 } ?: sizePattern.find(text)?.let { m ->
                val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                val unit = when (m.groupValues[2].uppercase()) { "TB" -> 1L shl 40; "GB" -> 1L shl 30; else -> 1L shl 20 }
                (n * unit).toLong()
            }
            val cached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED ||
                "⚡" in text || Regex("""\[(RD|AD|PM|TB|DL|ED|OC|PKP)\+]""").containsMatchIn(text)
            return StreamFacts(
                quality = quality,
                rank = QualityOrder.indexOf(quality).let { if (it < 0) QualityOrder.size else it },
                range = range,
                audio = listOfNotNull(codec, channels).joinToString(" ").ifEmpty { null },
                sizeBytes = size,
                cached = cached
            )
        }

        const val Other = "Other"
        val QualityOrder = listOf("4K", "1440p", "1080p", "720p", "SD", Other)
    }
}

internal enum class SourceSort { BEST, SIZE, ADDON }

private data class SourceGroup(val key: String, val streams: List<Pair<Stream, StreamFacts>>, val best: Boolean) {
    val lead get() = streams.first()
}

/**
 * The sources page's right side in Flix's design. [streams] come best first, as the page orders them; [onPlay] plays
 * one as the page's own list would.
 */
@Composable
fun FlixSourcesSection(
    streams: List<Stream>,
    isLoading: Boolean,
    error: String?,
    onPlay: (Stream) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val facts = remember(streams) { streams.map { it to StreamFacts.of(it) } }
    val tabs = remember(facts) { StreamFacts.QualityOrder.filter { q -> facts.any { it.second.quality == q } } }
    var tab by rememberSaveable { mutableStateOf<String?>(null) }
    val current = tab?.takeIf { it in tabs } ?: tabs.firstOrNull()
    var sort by rememberSaveable { mutableStateOf(SourceSort.BEST) }
    var open by remember { mutableStateOf<SourceGroup?>(null) }
    val best = streams.firstOrNull()
    val groups = remember(facts, current, sort) {
        val shown = facts.filter { it.second.quality == current }.let { list ->
            when (sort) {
                SourceSort.BEST -> list
                SourceSort.SIZE -> list.sortedByDescending { it.second.sizeBytes ?: -1L }
                SourceSort.ADDON -> list.sortedBy { it.first.addonName.lowercase() }
            }
        }
        shown.groupBy { it.first.addonName }.map { (addon, members) ->
            SourceGroup("$addon|$current", members, members.any { it.first === best })
        }
    }
    BackHandler(enabled = open != null) { open = null }
    Column(modifier.padding(start = 12.dp, end = 40.dp, top = 40.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.dexhub_sources_title), color = NuvioTheme.colors.TextPrimary, fontSize = 26.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it }) { quality ->
                    Chip(quality, selected = quality == current) { tab = quality; open = null }
                }
                item(key = "sort") {
                    Chip("⇅  " + stringResource(sort.label()), selected = false) {
                        sort = SourceSort.values()[(sort.ordinal + 1) % SourceSort.values().size]
                    }
                }
            }
            if (isLoading) Text(stringResource(R.string.dexhub_sources_looking), color = NuvioTheme.colors.TextTertiary, fontSize = 14.sp)
        }
        Spacer(Modifier.height(18.dp))
        val group = open
        AnimatedContent(
            targetState = group,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
            contentKey = { it?.key },
            label = "flixSources"
        ) { shown ->
            when {
                shown != null -> GroupList(shown, onPlay)
                groups.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        error ?: stringResource(if (isLoading) R.string.dexhub_sources_looking else R.string.dexhub_sources_none),
                        color = NuvioTheme.colors.TextSecondary, fontSize = 18.sp
                    )
                    if (!isLoading) {
                        Spacer(Modifier.height(16.dp))
                        Chip(stringResource(R.string.dexhub_sources_retry), selected = true, onClick = onRetry)
                    }
                }
                else -> {
                    val first = remember(current) { FocusRequester() }
                    LaunchedEffect(current) { runCatching { first.requestFocus() } }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(290.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 30.dp, top = 4.dp)
                    ) {
                        items(groups, key = { it.key }) { g ->
                            GroupCard(
                                g,
                                Modifier.animateItem().then(if (g == groups.first()) Modifier.focusRequester(first) else Modifier)
                            ) { if (g.streams.size == 1) onPlay(g.lead.first) else open = g }
                        }
                    }
                }
            }
        }
    }
}

private fun SourceSort.label(): Int = when (this) {
    SourceSort.BEST -> R.string.dexhub_sources_sort_best
    SourceSort.SIZE -> R.string.dexhub_sources_sort_size
    SourceSort.ADDON -> R.string.dexhub_sources_sort_addon
}

@Composable
private fun GroupCard(group: SourceGroup, modifier: Modifier, onClick: () -> Unit) {
    val (lead, facts) = group.lead
    val shape = RoundedCornerShape(18.dp)
    DexHubCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(168.dp),
        shape = shape,
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f))))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center) {
                    if (lead.addonLogo != null) {
                        AsyncImage(lead.addonLogo, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(3.dp))
                    } else {
                        Text(lead.addonName.take(1).uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (group.streams.size > 1) {
                    Text("+${group.streams.size - 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                if (group.best) Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Color(0xFFFACC15),
                    modifier = Modifier.size(20.dp))
            }
            Text(lead.addonName, color = NuvioTheme.colors.TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(facts.quality, color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                facts.range?.let { Pill(it) }
                facts.audio?.let { Pill(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                facts.size?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Movie, contentDescription = null, tint = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(16.dp))
                        Text(it, color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp)
                    }
                }
                if (facts.cached) CachedMark()
            }
        }
    }
}

/** Ready at once in the account: Flix's green bolt. */
@Composable
private fun CachedMark() {
    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
}

@Composable
private fun Pill(text: String) {
    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 3.dp))
}

/** One add-on's streams at one quality, each as the add-on names it; Back returns to the cards. */
@Composable
private fun GroupList(group: SourceGroup, onPlay: (Stream) -> Unit) {
    val first = remember(group.key) { FocusRequester() }
    LaunchedEffect(group.key) { runCatching { first.requestFocus() } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        items(group.streams, key = { System.identityHashCode(it.first) }) { (stream, facts) ->
            val shape = RoundedCornerShape(14.dp)
            DexHubCard(
                onClick = { onPlay(stream) },
                modifier = Modifier.animateItem().fillMaxWidth()
                    .then(if (stream === group.streams.first().first) Modifier.focusRequester(first) else Modifier),
                shape = shape,
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.07f),
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(facts.quality, color = NuvioTheme.colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        facts.range?.let { Pill(it) }
                        facts.audio?.let { Pill(it) }
                        Spacer(Modifier.weight(1f))
                        facts.size?.let { Text(it, color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp) }
                        if (facts.cached) CachedMark()
                    }
                    Text(
                        listOfNotNull(stream.title, stream.description).joinToString("\n").ifEmpty { stream.name.orEmpty() },
                        color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    DexHubCard(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = shape,
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = NuvioTheme.colors.Secondary
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
