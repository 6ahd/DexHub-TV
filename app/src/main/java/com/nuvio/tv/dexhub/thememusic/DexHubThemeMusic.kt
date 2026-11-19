package com.nuvio.tv.dexhub.thememusic

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.domain.model.Meta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Dex Hub TV's theme music on title pages (DEXHUB.md), as Flix plays a title's theme song: a film's or a series'
 * theme from ThemerrDB (LizardByte's open database of theme songs on YouTube, by TMDB or IMDb id), its sound read
 * through the trailers' YouTube reader, played softly behind the page with a fade in and out. It pauses while a
 * trailer plays or the app is in the background, and stops when the page closes. An option in Layout > Title page.
 */
object DexHubThemeMusicPrefs {
    private const val PREFS = "dexhub_look"
    private const val KEY = "theme_music"
    private val state = MutableStateFlow(true)
    @Volatile private var loaded = false

    fun enabled(context: Context): StateFlow<Boolean> {
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

@HiltViewModel
class ThemeMusicViewModel @Inject constructor(
    private val tmdb: TmdbService,
    private val youTube: InAppYouTubeExtractor
) : ViewModel() {

    /** The theme's sound for a title, or null when ThemerrDB has none or it cannot be read. */
    suspend fun audioFor(meta: Meta): String? = withContext(Dispatchers.IO) {
        val key = "${meta.apiType}:${meta.id}"
        found[key]?.let { return@withContext it.ifEmpty { null } }
        val url = runCatching { lookUp(meta) }.onFailure { Log.w(Tag, "no theme for $key: ${it.message}") }.getOrNull()
        found[key] = url.orEmpty()
        url
    }

    private suspend fun lookUp(meta: Meta): String? {
        val series = meta.apiType.lowercase().let { it == "series" || it == "tv" }
        val imdb = (meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") })?.substringBefore(':')
        val tmdbId = meta.id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()
            ?: imdb?.let { tmdb.imdbToTmdb(it, if (series) "series" else "movie") }
        val pages = buildList {
            if (series) {
                tmdbId?.let { add("tv_shows/themoviedb/$it.json") }
            } else {
                tmdbId?.let { add("movies/themoviedb/$it.json") }
                imdb?.let { add("movies/imdb/$it.json") }
            }
        }
        val youTubeUrl = pages.firstNotNullOfOrNull { page ->
            runCatching {
                http.newCall(Request.Builder().url(ThemerrDb + page).build()).execute().use { answer ->
                    if (!answer.isSuccessful) null
                    else JSONObject(answer.body?.string().orEmpty()).optString("youtube_theme_url").takeIf { it.startsWith("http") }
                }
            }.getOrNull()
        } ?: return null
        val source = youTube.extractPlaybackSource(youTubeUrl) ?: return null
        return source.audioUrl ?: source.videoUrl
    }

    companion object {
        private const val Tag = "DexHubThemeMusic"
        private const val ThemerrDb = "https://app.lizardbyte.dev/ThemerrDB/"
        private val found = ConcurrentHashMap<String, String>()
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
}

/**
 * The page's theme music: resolved when [meta] arrives, faded in, looped softly; faded out while [paused] (a trailer
 * playing) and while the app is in the background; released when the page leaves the screen.
 */
@Composable
fun DexHubThemeMusic(meta: Meta?, paused: Boolean, viewModel: ThemeMusicViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val enabled by remember(context) { DexHubThemeMusicPrefs.enabled(context) }.collectAsState()
    if (!enabled || meta == null) return
    var audio by remember(meta.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(meta.id) { audio = viewModel.audioFor(meta) }
    val url = audio ?: return
    val player = remember(url) { buildPlayer(context, url) } ?: return
    var foreground by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> foreground = false
                Lifecycle.Event.ON_RESUME -> foreground = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val audible = foreground && !paused
    LaunchedEffect(player, audible) {
        // a fade of about a second and a half either way, in small steps
        if (audible) {
            if (!player.isPlaying) player.play()
            while (player.volume < Volume) {
                player.volume = (player.volume + Volume / 30f).coerceAtMost(Volume)
                delay(50)
            }
        } else {
            while (player.volume > 0f) {
                player.volume = (player.volume - Volume / 18f).coerceAtLeast(0f)
                delay(50)
            }
            player.pause()
        }
    }
}

private const val Volume = 0.32f

private fun buildPlayer(context: Context, url: String): ExoPlayer? = runCatching {
    val app = context.applicationContext
    val audioOnly = RenderersFactory { handler, _, audioListener, _, _ ->
        arrayOf(MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener))
    }
    ExoPlayer.Builder(app, audioOnly)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            false
        )
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
}.onFailure { Log.w("DexHubThemeMusic", "player not built", it) }.getOrNull()
