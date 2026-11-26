package com.nuvio.tv.core.dexpicks

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.scout.ask.AskItem
import com.nuvio.tv.core.scout.ask.AskOutcome
import com.nuvio.tv.core.scout.ask.AskSavedItem
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.core.scout.ask.toMeta
import com.nuvio.tv.core.scout.ask.toSaved
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * plugin.video.dexhub's own Home rows that follow what the viewer watches:
 * - "Because you watched X": TMDB recommendations for the newest film and the newest series watched or started.
 * - "Recommended for you by AI • Films / Series": the viewer's own AI key picks from the last 25 titles watched.
 *   Offered only while a key is set; answers are kept a day, or until the history changes.
 */
@Singleton
class DexPicksCatalogProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val askService: dagger.Lazy<AskService>,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val watchProgress: dagger.Lazy<WatchProgressRepository>
) : BuiltInCatalogProvider {

    internal data class Seed(val contentId: String, val title: String)

    private val becauseCache = ConcurrentHashMap<String, Pair<Long, List<MetaPreview>>>()

    @Serializable
    private data class AiCache(val historyHash: String, val savedAt: Long, val items: List<AskSavedItem>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val aiLock = Mutex()
    private val aiRunning = ConcurrentHashMap.newKeySet<Boolean>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val rowChanges: Flow<Unit> = changes

    private val aiOffered: Flow<Boolean> =
        flow { emitAll(askService.get().keyChanges.map { it.activeConfig != null }) }
            .onStart { emit(false) }
            .distinctUntilChanged()

    private val seeds: Flow<Pair<Seed?, Seed?>> = flow {
        emit(Pair<Seed?, Seed?>(null, null))
        val repo = watchProgress.get()
        emitAll(combine(repo.allProgress, repo.watchedItems) { progress, watched ->
            val entries = entries(progress.map { Triple(it.lastWatched, it.contentType, Seed(it.contentId, it.name.trim())) },
                watched.map { Triple(it.watchedAt, it.contentType, Seed(it.contentId, it.title.trim())) })
            newestSeed(entries, series = false) to newestSeed(entries, series = true)
        })
    }.distinctUntilChanged()

    override val addons: Flow<List<Addon>> = flow {
        combine(seeds, aiOffered) { s, ai -> s to ai }.collect { (seedPair, ai) ->
            val (movieSeed, seriesSeed) = seedPair
            val because = text(R_BECAUSE)
            val catalogs = buildList {
                if (ai) {
                    add(descriptor(AI_MOVIES, text(R_AI_MOVIES)))
                    add(descriptor(AI_SERIES, text(R_AI_SERIES)))
                }
                movieSeed?.let { add(descriptor(BECAUSE_MOVIES, because.format(it.title))) }
                seriesSeed?.let { add(descriptor(BECAUSE_SERIES, because.format(it.title))) }
            }
            emit(
                if (catalogs.isEmpty()) emptyList()
                else listOf(
                    Addon(
                        id = ADDON_ID,
                        name = ADDON_NAME,
                        version = "1",
                        description = null,
                        logo = null,
                        baseUrl = BASE_URL,
                        catalogs = catalogs,
                        types = listOf(ContentType.MOVIE, ContentType.SERIES),
                        resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
                    )
                )
            )
        }
    }.distinctUntilChanged()

    override fun handles(baseUrl: String?): Boolean = baseUrl == BASE_URL

    override suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): CatalogRow {
        val series = catalogId == BECAUSE_SERIES || catalogId == AI_SERIES
        val items = if (skip > 0) emptyList() else when (catalogId) {
            BECAUSE_MOVIES, BECAUSE_SERIES -> because(series)
            AI_MOVIES, AI_SERIES -> aiPicks(series)
            else -> emptyList()
        }
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            items = items,
            hasMore = false
        )
    }

    private suspend fun because(series: Boolean): List<MetaPreview> {
        val seed = currentSeed(series) ?: return emptyList()
        becauseCache[seed.contentId]?.takeIf { System.currentTimeMillis() - it.first < BECAUSE_TTL_MS }?.let { return it.second }
        val language = runCatching { tmdbSettings.settings.first().language }.getOrDefault("en")
        val results = try {
            val tmdbId = tmdbService.ensureTmdbId(seed.contentId, if (series) "series" else "movie")?.toIntOrNull()
                ?: return emptyList()
            val response = if (series) tmdbApi.getTvRecommendations(tmdbId, BuildConfig.TMDB_API_KEY, language)
            else tmdbApi.getMovieRecommendations(tmdbId, BuildConfig.TMDB_API_KEY, language)
            response.body()?.results.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val metas = results.filter { !it.posterPath.isNullOrBlank() }.take(BECAUSE_MAX).mapNotNull { it.toMeta(series) }
        if (metas.isNotEmpty()) becauseCache[seed.contentId] = System.currentTimeMillis() to metas
        return metas
    }

    private suspend fun currentSeed(series: Boolean): Seed? {
        val repo = watchProgress.get()
        val entries = entries(
            repo.allProgress.first().map { Triple(it.lastWatched, it.contentType, Seed(it.contentId, it.name.trim())) },
            repo.watchedItems.first().map { Triple(it.watchedAt, it.contentType, Seed(it.contentId, it.title.trim())) }
        )
        return newestSeed(entries, series)
    }

    private fun TmdbRecommendationResult.toMeta(series: Boolean): MetaPreview? {
        val shown = (title ?: name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val date = (if (series) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
        return MetaPreview(
            id = "tmdb:$id",
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (series) "series" else "movie",
            name = shown,
            poster = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            posterShape = PosterShape.POSTER,
            background = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
            released = date,
            imdbRating = voteAverage?.takeIf { it > 0 }?.toFloat(),
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    // ---- Recommended for you by AI -------------------------------------------------------------

    /** Kept answers at once; a stale or missing answer is asked for in the background and the row re-read after. */
    private suspend fun aiPicks(series: Boolean): List<MetaPreview> {
        val service = askService.get()
        if (!service.hasKey()) return emptyList()
        val hash = hashOf(service.historyLines(series))
        val cached = readAi(series)
        val fresh = cached != null && cached.historyHash == hash &&
            System.currentTimeMillis() - cached.savedAt < AI_TTL_MS
        if (!fresh) refreshAi(series, hash)
        return cached?.items.orEmpty().map(AskSavedItem::toMeta)
    }

    private fun refreshAi(series: Boolean, hash: String) {
        if (!aiRunning.add(series)) return
        scope.launch {
            try {
                aiLock.withLock {
                    val outcome = askService.get().historyPicks(series)
                    if (outcome is AskOutcome.Answer && outcome.items.isNotEmpty()) {
                        writeAi(series, AiCache(hash, System.currentTimeMillis(), outcome.items.map(AskItem::toSaved)))
                        changes.tryEmit(Unit)
                    }
                }
            } finally {
                aiRunning.remove(series)
            }
        }
    }

    private fun aiFile(series: Boolean) = File(context.cacheDir, "dexpicks/ai_${if (series) "series" else "movies"}_${lang()}.json")

    private suspend fun readAi(series: Boolean): AiCache? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<AiCache>(aiFile(series).readText()) }.getOrNull()
    }

    private suspend fun writeAi(series: Boolean, cache: AiCache) = withContext(Dispatchers.IO) {
        runCatching {
            val file = aiFile(series)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(cache))
        }
    }

    private fun hashOf(lines: List<String>): String =
        MessageDigest.getInstance("SHA-1").digest(lines.take(10).joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun descriptor(id: String, name: String) =
        CatalogDescriptor(
            type = if (id == BECAUSE_SERIES || id == AI_SERIES) ContentType.SERIES else ContentType.MOVIE,
            id = id,
            name = name,
            showInHome = true,
            hasExplicitShowInHome = true
        )

    companion object {
        const val ADDON_ID = "dexpicks"
        const val ADDON_NAME = "Dex Hub"
        const val BASE_URL = BuiltInCatalogs.BASE_PREFIX + "dexpicks"

        const val AI_MOVIES = "ai_movies"
        const val AI_SERIES = "ai_series"
        const val BECAUSE_MOVIES = "because_movies"
        const val BECAUSE_SERIES = "because_series"

        private const val R_BECAUSE = 0
        private const val R_AI_MOVIES = 1
        private const val R_AI_SERIES = 2
        private val EN = listOf("Because you watched %s", "Recommended for you by AI • Films", "Recommended for you by AI • Series")
        private val AR = listOf("لأنك شاهدت %s", "مقترح لك بالذكاء الاصطناعي • أفلام", "مقترح لك بالذكاء الاصطناعي • مسلسلات")
        private const val AI_TTL_MS = 24L * 60 * 60 * 1000

        private val SERIES_TYPES = setOf("series", "tv", "show", "episode")
        private const val BECAUSE_MAX = 24
        private const val BECAUSE_TTL_MS = 24L * 60 * 60 * 1000

        internal fun entries(vararg lists: List<Triple<Long, String, Seed>>): List<Triple<Long, String, Seed>> =
            lists.flatMap { it }

        /** The newest title of that kind; ids-as-names (tt…) are skipped. */
        internal fun newestSeed(entries: List<Triple<Long, String, Seed>>, series: Boolean): Seed? =
            entries.sortedByDescending { it.first }.firstOrNull { (_, type, seed) ->
                val kind = type.lowercase()
                (if (series) kind in SERIES_TYPES else kind == "movie") &&
                    seed.title.isNotBlank() && !seed.title.startsWith("tt")
            }?.third

        private fun lang(): String {
            val tag = LocaleCache.localeTag.takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
                ?: java.util.Locale.getDefault().language
            return if (tag.startsWith("ar")) "ar" else "en"
        }

        /** plugin.video.dexhub's own wording (i18n.py), in the app's language. */
        private fun text(key: Int): String = if (lang() == "ar") AR[key] else EN[key]
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DexPicksCatalogModule {
    @Binds
    @IntoSet
    abstract fun provider(impl: DexPicksCatalogProvider): BuiltInCatalogProvider
}
