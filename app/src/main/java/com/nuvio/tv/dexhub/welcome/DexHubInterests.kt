@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.welcome

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dex Hub TV's first-run question (DEXHUB.md): what the viewer likes to watch. The answers put Live TV or DexWorld
 * right after Home in the side menu ([arrangeMenu]) and DexWorld's matching sections first ([rank]); they can be
 * changed in Layout > Home. Kept on this device.
 */
enum class Interest(val label: Int, val icon: String, val words: List<String>) {
    GULF(R.string.dexhub_like_gulf, "🌴", listOf("خليج", "gulf", "kuwait", "saudi", "سعود", "كويت")),
    EGYPTIAN(R.string.dexhub_like_egypt, "🏛️", listOf("مصر", "egypt")),
    TURKISH(R.string.dexhub_like_turkish, "🌙", listOf("ترك", "turk")),
    LEVANTINE(R.string.dexhub_like_levant, "🌿", listOf("شام", "سوري", "لبنان", "levant", "syria", "leban")),
    ARABIC_FILMS(R.string.dexhub_like_arabic_films, "🎬", listOf("أفلام عرب", "arabic film", "arabic movie", "عربي")),
    HOLLYWOOD(R.string.dexhub_like_hollywood, "⭐", listOf("هوليوود", "hollywood", "أجنبي", "foreign")),
    KOREAN(R.string.dexhub_like_korean, "🌸", listOf("كوري", "korea", "k-drama", "آسيو", "asia")),
    ANIME(R.string.dexhub_like_anime, "🐉", listOf("أنمي", "انمي", "anime")),
    KIDS(R.string.dexhub_like_kids, "🧸", listOf("أطفال", "كرتون", "kids", "cartoon", "family", "عائل")),
    DOCUMENTARY(R.string.dexhub_like_docs, "🌍", listOf("وثائق", "document")),
    LIVE_TV(R.string.dexhub_like_live, "📺", emptyList()),
    SPORTS(R.string.dexhub_like_sports, "⚽", listOf("رياض", "sport"));

    companion object {
        /** The DexWorld kinds: any of these puts DexWorld high in the menu. */
        val Sections = setOf(GULF, EGYPTIAN, TURKISH, LEVANTINE, ARABIC_FILMS, KOREAN, ANIME, KIDS, DOCUMENTARY, HOLLYWOOD)
    }
}

object DexHubInterests {
    private const val PREFS = "dexhub_look"
    private const val KEY = "interests"
    private const val ASKED = "interests_asked"
    private val chosen = MutableStateFlow<Set<Interest>>(emptySet())
    private val asked = MutableStateFlow(true)
    @Volatile private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            chosen.value = prefs.getStringSet(KEY, emptySet()).orEmpty()
                .mapNotNull { name -> Interest.entries.firstOrNull { it.name == name } }.toSet()
            asked.value = prefs.getBoolean(ASKED, false)
            loaded = true
        }
    }

    fun chosen(context: Context): StateFlow<Set<Interest>> = chosen.also { load(context) }

    /** False until the viewer answered or skipped the first-run question. */
    fun asked(context: Context): StateFlow<Boolean> = asked.also { load(context) }

    fun save(context: Context, interests: Set<Interest>) {
        prefs(context).edit().putStringSet(KEY, interests.map { it.name }.toSet()).putBoolean(ASKED, true).apply()
        loaded = true
        chosen.value = interests
        asked.value = true
    }

    /** Ask again (Layout > Home): the question shows on the next visit to Home. */
    fun askAgain(context: Context) {
        prefs(context).edit().putBoolean(ASKED, false).apply()
        loaded = true
        asked.value = false
    }

    /**
     * The side menu in the viewer's order: Live TV first after Home for live TV or sports, then DexWorld for any of
     * its kinds; the rest as Arc lays it out.
     */
    fun <T> arrangeMenu(items: List<T>, interests: Set<Interest>, route: (T) -> String, home: String,
                        liveTv: String, dexWorld: String): List<T> {
        val first = buildList {
            if (Interest.LIVE_TV in interests || Interest.SPORTS in interests) add(liveTv)
            if (interests.any { it in Interest.Sections }) add(dexWorld)
        }
        if (first.isEmpty()) return items
        val lifted = first.mapNotNull { r -> items.firstOrNull { route(it) == r } }
        val rest = items.filterNot { it in lifted }
        val at = rest.indexOfFirst { route(it) == home } + 1
        return rest.take(at) + lifted + rest.drop(at)
    }

    /** How well a DexWorld section's name matches the viewer's interests (higher first; 0 for none). */
    fun rank(title: String, interests: Set<Interest>): Int {
        val text = title.lowercase()
        return interests.count { interest -> interest.words.any { it.lowercase() in text } }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The first-run question over Home: shown until it is answered or skipped. */
@Composable
fun DexHubWelcome() {
    val context = LocalContext.current
    val asked by remember(context) { DexHubInterests.asked(context) }.collectAsState()
    AnimatedVisibility(visible = !asked, enter = fadeIn(tween(400)), exit = fadeOut(tween(300))) {
        WelcomePanel(onDone = { DexHubInterests.save(context, it) })
    }
}

private const val Columns = 4

@Composable
private fun WelcomePanel(onDone: (Set<Interest>) -> Unit) {
    var picked by remember { mutableStateOf(emptySet<Interest>()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val accent = NuvioTheme.colors.Secondary
    val background = NuvioTheme.colors.Background
    Box(
        Modifier.fillMaxSize()
            .background(background)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(960f, 0f), radius = 1400f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.dexhub_intro_mark), contentDescription = null,
                modifier = Modifier.width(120.dp).height(59.dp))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.dexhub_welcome_title), color = NuvioTheme.colors.TextPrimary, fontSize = 32.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dexhub_welcome_sub), color = NuvioTheme.colors.TextSecondary, fontSize = 16.sp,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            val rows = Interest.entries.chunked(Columns)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEachIndexed { c, interest ->
                            InterestCard(
                                interest, selected = interest in picked, delayMs = (r * Columns + c) * 40,
                                modifier = Modifier
                                    .then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)
                                    .focusProperties {
                                        if (c == 0) left = FocusRequester.Cancel
                                        if (c == row.lastIndex) right = FocusRequester.Cancel
                                        if (r == 0) up = FocusRequester.Cancel
                                    }
                            ) { picked = if (interest in picked) picked - interest else picked + interest }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Action(stringResource(R.string.dexhub_welcome_go), strong = true,
                    modifier = Modifier.focusProperties { left = FocusRequester.Cancel; down = FocusRequester.Cancel }
                ) { onDone(picked) }
                Action(stringResource(R.string.dexhub_welcome_skip), strong = false,
                    modifier = Modifier.focusProperties { right = FocusRequester.Cancel; down = FocusRequester.Cancel }
                ) { onDone(emptySet()) }
            }
        }
    }
}

@Composable
private fun InterestCard(interest: Interest, selected: Boolean, delayMs: Int, modifier: Modifier, onClick: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        shown = true
    }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(380), label = "interestAppear")
    val shape = RoundedCornerShape(18.dp)
    val accent = NuvioTheme.colors.Secondary
    Card(
        onClick = onClick,
        modifier = modifier.size(width = 210.dp, height = 96.dp).alpha(appear),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.07f),
            focusedContainerColor = if (selected) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.16f)
        ),
        border = CardDefaults.border(
            border = if (selected) Border(androidx.compose.foundation.BorderStroke(2.dp, accent), shape = shape) else Border.None,
            focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(interest.icon, fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(interest.label), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, modifier = Modifier.weight(1f))
            AnimatedVisibility(selected, enter = scaleIn(tween(200)) + fadeIn(), exit = fadeOut(tween(120))) {
                Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Action(label: String, strong: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Card(
        onClick = onClick,
        modifier = modifier.size(width = 220.dp, height = 54.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (strong) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = if (strong) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.2f)
        ),
        border = CardDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.06f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
