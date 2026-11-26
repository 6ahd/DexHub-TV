package com.nuvio.tv.core.scout.ask

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val LOOKUPS_AT_ONCE = 6
private const val MAX_BUSY_TRIES = 4
private const val BACKOFF_MS = 1_000L
private const val JITTER_MS = 500L
private const val RETRY_BUDGET_MS = 40_000L
private const val MODEL_RECHECK_MS = 7L * 24 * 60 * 60 * 1000
private const val MAX_RESPONSE_BYTES = 512L * 1024
private const val MAX_OVERVIEW = 400
private val SERIES_TYPES = setOf("series", "tv", "show", "episode")

interface AskTransport {
    /** Throws IOException when the service cannot be reached. */
    suspend fun send(request: AskHttpRequest): AskHttpResponse
}

/** Its own client: no logging interceptor, and no redirects, so a key header never follows one. */
@Singleton
class OkHttpAskTransport @Inject constructor() : AskTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun send(request: AskHttpRequest): AskHttpResponse = runInterruptible(Dispatchers.IO) {
        val builder = Request.Builder()
        try {
            builder.url(request.url)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
        } catch (_: IllegalArgumentException) {
            throw IOException("Bad request settings")
        }
        request.body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
        client.newCall(builder.build()).execute().use { response ->
            val source = response.body.source()
            source.request(MAX_RESPONSE_BYTES)
            AskHttpResponse(response.code, source.buffer.clone().readUtf8())
        }
    }
}

data class AskItem(val meta: MetaPreview, val reason: String)

sealed interface AskOutcome {
    data class Answer(val rowName: String, val items: List<AskItem>) : AskOutcome
    data class Failed(val failure: AskFailure) : AskOutcome
}

interface AskTitleLookup {
    suspend fun lookup(pick: AskPick): MetaPreview?
}

@Singleton
class TmdbAskTitleLookup @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val settings: TmdbSettingsDataStore
) : AskTitleLookup {
    override suspend fun lookup(pick: AskPick): MetaPreview? {
        val language = settings.settings.first().language
        val order = if (pick.isSeries == true) listOf(true, false) else listOf(false, true)
        for (series in order) {
            val results = try {
                val response = if (series) tmdbApi.searchTv(BuildConfig.TMDB_API_KEY, pick.title, language)
                else tmdbApi.searchMovies(BuildConfig.TMDB_API_KEY, pick.title, language)
                response.body()?.results.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            bestTitleMatch(results, pick)?.toMeta(series)?.let { return it }
        }
        return null
    }

    private fun TmdbDiscoverResult.toMeta(series: Boolean): MetaPreview? {
        val shown = (title ?: name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val date = (if (series) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
        return MetaPreview(
            id = "tmdb:$id",
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (series) "series" else "movie",
            name = shown,
            poster = image(posterPath, "w500") ?: image(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = image(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
            released = date,
            imdbRating = voteAverage?.toFloat(),
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}

internal class AskEngine(
    private val transport: AskTransport,
    private val lookup: AskTitleLookup,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val newest = ConcurrentHashMap<AskProvider, Pair<String, Long>>()

    private suspend fun modelFor(config: AskConfig): String {
        val family = config.provider.family ?: return config.baseModel
        newest[config.provider]?.takeIf { now() - it.second < MODEL_RECHECK_MS }?.let { return it.first }
        val found = try {
            val response = transport.send(AskConnectors.modelsRequest(config))
            if (response.code in 200..299) AskConnectors.newestOfFamily(family, response.body) else null
        } catch (_: IOException) {
            null
        } ?: return config.baseModel
        newest[config.provider] = found to now()
        return found
    }

    private suspend fun attempt(config: AskConfig, prompt: AskPrompt, model: String): AskReply = try {
        AskConnectors.parseReply(config, transport.send(AskConnectors.answerRequest(config, prompt, model)))
    } catch (_: IOException) {
        AskReply.Failed(AskFailure.NETWORK)
    }

    /**
     * A busy service gets a few tries with growing waits, moving down its models because demand and
     * free limits are per model. No answer at all gets one more try: each can take the full time limit.
     */
    private suspend fun reply(config: AskConfig, prompt: AskPrompt): AskReply {
        val model = modelFor(config)
        val chain = listOf(model) + config.provider.fallbacks
        val started = now()
        var result = attempt(config, prompt, model)
        var tries = 1
        while (true) {
            val failure = (result as? AskReply.Failed)?.failure ?: return result
            if (failure == AskFailure.MODEL_GONE && model != config.baseModel && tries == 1) {
                newest.remove(config.provider)
                result = attempt(config, prompt, config.baseModel)
                tries++
                continue
            }
            val retry = when (failure) {
                AskFailure.BUSY -> tries < MAX_BUSY_TRIES
                AskFailure.NETWORK -> tries < 2
                AskFailure.RATE_LIMITED -> tries < chain.size
                else -> false
            }
            if (!retry || now() - started > RETRY_BUDGET_MS) return result
            if (failure != AskFailure.RATE_LIMITED) delay((BACKOFF_MS shl (tries - 1)) + Random.nextLong(JITTER_MS))
            result = attempt(config, prompt, chain[minOf(tries, chain.lastIndex)])
            tries++
        }
    }

    /** Picks from what was watched lately; anything already seen or started is dropped, never shown. */
    suspend fun picksFromHistory(config: AskConfig, history: List<String>, series: Boolean, answerLanguage: String, seen: AskSeen): AskOutcome {
        val text = when (val reply = reply(config, buildHistoryPicksPrompt(history, series, answerLanguage))) {
            is AskReply.Failed -> return AskOutcome.Failed(reply.failure)
            is AskReply.Text -> reply
        }
        val parsed = AskConnectors.parseAnswer(text.text, text.cutOff)
            ?: return AskOutcome.Failed(if (text.cutOff) AskFailure.CUT_OFF else AskFailure.BAD_ANSWER)
        val wanted = if (series) ContentType.SERIES else ContentType.MOVIE
        val found = lookUpAll(parsed.picks.map { it.copy(isSeries = series) })
            .filter { it.meta.type == wanted && !seen.blocks(it.meta) }
        return if (found.isEmpty()) AskOutcome.Failed(AskFailure.NOTHING_FOUND) else AskOutcome.Answer(parsed.rowName, found)
    }

    suspend fun testKey(config: AskConfig): AskKeyTest = try {
        AskConnectors.parseKeyTest(config, transport.send(AskConnectors.modelsRequest(config)))
    } catch (_: IOException) {
        AskKeyTest.Failed(AskFailure.NETWORK)
    }

    private suspend fun lookUpAll(picks: List<AskPick>): List<AskItem> = coroutineScope {
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        picks.map { pick ->
            async { gate.withPermit { lookup.lookup(pick) }?.let { AskItem(it, pick.reason) } }
        }.awaitAll().filterNotNull().distinctBy { it.meta.id }
    }
}

/** Dex Hub's AI: only the viewer's recent titles and the answer language ever leave the box. */
@Singleton
class AskService @Inject constructor(
    @ApplicationContext private val context: Context,
    transport: OkHttpAskTransport,
    lookup: TmdbAskTitleLookup,
    private val vault: AskKeyVault,
    private val watchProgress: dagger.Lazy<WatchProgressRepository>
) {
    private val engine = AskEngine(transport, lookup)

    /** Whether a key is set for this profile, so AI rows can be offered. */
    fun hasKey(): Boolean = vault.current().activeConfig != null

    val keyChanges get() = vault.keys

    /**
     * "Recommended for you by AI": films or series picked from the last [HISTORY_PICKS_MAX_LINES] titles of that
     * kind watched or started, newest first. Fewer than two gives [AskFailure.NOTHING_FOUND].
     */
    suspend fun historyPicks(series: Boolean): AskOutcome {
        val config = vault.current().activeConfig ?: return AskOutcome.Failed(AskFailure.NO_KEY)
        val lines = historyLines(series)
        if (lines.size < 2) return AskOutcome.Failed(AskFailure.NOTHING_FOUND)
        return engine.picksFromHistory(config, lines, series, answerLanguage(), seen())
    }

    /** "Title 2010 (movie)" lines, newest first, one per title. */
    suspend fun historyLines(series: Boolean): List<String> {
        val kind = if (series) "series" else "movie"
        fun matches(type: String) = if (series) type.lowercase() in SERIES_TYPES else type.lowercase() == "movie"
        val years = HashMap<String, String>()
        val entries = ArrayList<Pair<Long, Pair<String, String>>>()
        watchProgress.get().watchedItems.first().filter { matches(it.contentType) }.forEach { item ->
            item.releaseInfo?.let { Regex("\\d{4}").find(it)?.value }?.let { years[item.contentId] = it }
            entries += item.watchedAt to (item.contentId to item.title)
        }
        watchProgress.get().allProgress.first().filter { matches(it.contentType) }.forEach { item ->
            entries += item.lastWatched to (item.contentId to item.name)
        }
        val seenIds = HashSet<String>()
        val seenNames = HashSet<String>()
        return entries.sortedByDescending { it.first }.mapNotNull { (_, idName) ->
            val (id, name) = idName
            val title = name.trim()
            if (title.isBlank() || title.startsWith("tt")) return@mapNotNull null
            if (!seenIds.add(id) || !seenNames.add(title.lowercase())) return@mapNotNull null
            listOfNotNull(title, years[id]).joinToString(" ") + " ($kind)"
        }.take(HISTORY_PICKS_MAX_LINES)
    }

    suspend fun testKey(config: AskConfig): AskKeyTest = engine.testKey(config)

    private suspend fun seen(): AskSeen {
        val watched = watchProgress.get().watchedItems.first()
        val started = watchProgress.get().allProgress.first()
        return withContext(Dispatchers.Default) {
            val watchedNames = watched.mapTo(HashSet()) { askSeenName(it.title, it.releaseInfo) }
            AskSeen(
                ids = (watched.map { it.contentId } + started.map { it.contentId }).toSet(),
                names = watchedNames + started.map { normaliseTitle(it.name) },
                watchedIds = watched.mapTo(HashSet()) { it.contentId },
                watchedNames = watchedNames
            )
        }
    }

    private fun answerLanguage(): String {
        val tag = context.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        val locale = if (tag.isNullOrEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    }
}

@Serializable
data class AskSavedItem(
    val id: String,
    val series: Boolean,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: Float? = null,
    val reason: String = ""
)

internal fun AskItem.toSaved() = AskSavedItem(
    id = meta.id,
    series = meta.type == ContentType.SERIES,
    name = meta.name,
    poster = meta.poster,
    background = meta.background,
    overview = meta.description?.take(MAX_OVERVIEW),
    year = meta.releaseInfo,
    rating = meta.imdbRating,
    reason = reason
)

internal fun AskSavedItem.toMeta() = MetaPreview(
    id = id,
    type = if (series) ContentType.SERIES else ContentType.MOVIE,
    rawType = if (series) "series" else "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = background,
    logo = null,
    description = overview,
    releaseInfo = year,
    imdbRating = rating,
    genres = emptyList()
)

@Module
@InstallIn(SingletonComponent::class)
abstract class AskModule {
    @Binds
    @IntoSet
    abstract fun bindKeys(vault: AskKeyVault): ProfileScopedCredentialStore
}
