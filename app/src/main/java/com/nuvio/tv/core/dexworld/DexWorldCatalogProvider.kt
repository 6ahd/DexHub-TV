package com.nuvio.tv.core.dexworld

import android.content.Context
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DexWorld (blocks.dexworld.cc), the same source plugin.video.dexhub reads for its kits: Arabic,
 * TV networks, streamers, Top 10 on streamers and studios. Each kit is a Home row of ready-made
 * cover tiles; a tile opens its list of titles, 40 a page.
 */
@Singleton
class DexWorldCatalogProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : BuiltInCatalogProvider {

    private val page = MutableStateFlow<DexWorldPage?>(null)

    /** Kit ids as plugin.video.dexhub's Home shows them, in its order. */
    private val homeKits = listOf("k:arabic", "k:networks", "k:platforms", "k:top10", "k:studios")

    private val pageFlow: Flow<DexWorldPage?> = LocaleCache.tags.map { lang() }.distinctUntilChanged()
        .flatMapLatest { lang ->
            flow {
                emit(readCache(lang))
                fetchPage(lang)?.let { emit(it) }
            }
        }
        .onStart { emit(page.value) }

    override val addons: Flow<List<Addon>> = pageFlow.map { current ->
        page.value = current ?: page.value
        val kits = current?.kits.orEmpty()
        if (kits.isEmpty()) return@map emptyList()
        listOf(
            Addon(
                id = ADDON_ID,
                name = "DexWorld",
                version = "1",
                description = null,
                logo = null,
                baseUrl = BASE_URL,
                catalogs = kits.flatMap { it.members }.distinctBy { it.id }.map { member ->
                    CatalogDescriptor(
                        type = ContentType.UNKNOWN,
                        rawType = ROW_TYPE,
                        id = catalogIdOf(member.id),
                        name = member.title,
                        showInHome = false
                    )
                },
                types = listOf(ContentType.MOVIE, ContentType.SERIES),
                resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
            )
        )
    }.distinctUntilChanged()

    override val collections: Flow<List<Collection>> = pageFlow.map { current ->
        val kits = current?.kits.orEmpty().associateBy { it.id }
        homeKits.mapNotNull { kits[it] }.filter { it.members.isNotEmpty() }.map { kit ->
            Collection(
                id = collectionIdOf(kit.id),
                title = kit.title,
                showAllTab = false,
                folders = kit.members.map { member ->
                    CollectionFolder(
                        id = catalogIdOf(member.id),
                        title = member.title,
                        coverImageUrl = member.cover,
                        tileShape = PosterShape.LANDSCAPE,
                        hideTitle = !member.cover.isNullOrBlank(),
                        focusGifEnabled = false,
                        sources = listOf(AddonCatalogCollectionSource(ADDON_ID, ROW_TYPE, catalogIdOf(member.id)))
                    )
                }
            )
        }
    }.distinctUntilChanged()

    override val defaultFirstKeys: List<String>
        get() = homeKits.map { "collection_" + collectionIdOf(it) }

    /** After Scout's own rows, like the add-on puts its kits after the Continue rows. */
    override val defaultFirstRank: Int get() = 2

    override fun handles(baseUrl: String?): Boolean = baseUrl == BASE_URL

    override fun ownsAddon(addonId: String?): Boolean = addonId == ADDON_ID

    override fun showsTypeSuffix(addonId: String): Boolean = false

    /** Each list lives inside its kit's tiles; on its own it stays off Home until switched on. */
    override fun hiddenByDefaultKeys(addons: List<Addon>): List<String> =
        addons.filter { it.id == ADDON_ID }.flatMap { addon ->
            addon.catalogs.map { com.nuvio.tv.domain.model.catalogRowLegacyKey(ADDON_ID, ROW_TYPE, it.id) }
        }

    override suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        posterPattern: String
    ): CatalogRow {
        val pageNo = skip / PAGE_SIZE + 1
        val list = withContext(Dispatchers.IO) { fetchRow(blockIdOf(catalogId), lang(), pageNo) }
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.UNKNOWN,
            rawType = ROW_TYPE,
            items = list?.items.orEmpty(),
            hasMore = list != null && list.page < list.pages,
            currentPage = pageNo - 1,
            supportsSkip = true,
            skipStep = PAGE_SIZE,
            nextSkip = pageNo * PAGE_SIZE
        )
    }

    private fun lang(): String {
        val tag = LocaleCache.localeTag.takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
            ?: java.util.Locale.getDefault().language
        return if (tag.startsWith("ar")) "ar" else "en"
    }

    private fun cacheFile(lang: String) = File(context.cacheDir, "dexworld/page_$lang.json")

    private suspend fun readCache(lang: String): DexWorldPage? = withContext(Dispatchers.IO) {
        runCatching { parsePage(JSONObject(cacheFile(lang).readText())) }.getOrNull()
    }

    private suspend fun fetchPage(lang: String): DexWorldPage? = withContext(Dispatchers.IO) {
        val file = cacheFile(lang)
        // Fresh on disk (often written a moment ago for the other flow): use it as it is.
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < PAGE_TTL_MS) {
            return@withContext runCatching { parsePage(JSONObject(file.readText())) }.getOrNull()
        }
        val body = get("$API/api/dexhub?lang=$lang") ?: return@withContext null
        runCatching {
            val parsed = parsePage(JSONObject(body))
            file.parentFile?.mkdirs()
            file.writeText(body)
            parsed
        }.getOrNull()
    }

    private fun fetchRow(blockId: String, lang: String, pageNo: Int): DexWorldList? {
        val file = File(context.cacheDir, "dexworld/row_${lang}_${blockId.replace(Regex("[^A-Za-z0-9_-]"), "_")}_$pageNo.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < ROW_TTL_MS
        val body = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: get("$API/api/dexhub/row?id=${URLEncoder.encode(blockId, "UTF-8")}&lang=$lang&page=$pageNo")
                ?.also { text -> runCatching { file.parentFile?.mkdirs(); file.writeText(text) } }
            ?: runCatching { file.readText() }.getOrNull()
            ?: return null
        return runCatching { parseList(JSONObject(body)) }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parsePage(root: JSONObject): DexWorldPage {
        val data = root.optJSONObject("data") ?: root
        val rows = data.optJSONArray("rows") ?: JSONArray()
        val kits = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (row.optString("kind") != "kit") return@mapNotNull null
            val members = row.optJSONArray("members") ?: JSONArray()
            DexWorldKit(
                id = row.optString("id"),
                title = row.optString("title"),
                members = (0 until members.length()).mapNotNull { j ->
                    val member = members.optJSONObject(j) ?: return@mapNotNull null
                    if (!member.optBoolean("ready", true)) return@mapNotNull null
                    DexWorldMember(
                        id = member.optString("id"),
                        title = member.optString("title"),
                        cover = member.optString("cover").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
            )
        }
        return DexWorldPage(kits)
    }

    private fun parseList(root: JSONObject): DexWorldList {
        val data = root.optJSONObject("data") ?: root
        val items = data.optJSONArray("items") ?: JSONArray()
        return DexWorldList(
            page = data.optInt("page", 1),
            pages = data.optInt("pages", 1),
            items = (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val tmdb = item.optInt("tmdb", 0).takeIf { it > 0 } ?: return@mapNotNull null
                val tv = item.optString("type") == "tv"
                MetaPreview(
                    id = "tmdb:$tmdb",
                    type = if (tv) ContentType.SERIES else ContentType.MOVIE,
                    rawType = if (tv) "series" else "movie",
                    name = item.optString("title"),
                    poster = item.optString("poster").takeIf { it.startsWith("http") },
                    posterShape = PosterShape.POSTER,
                    background = item.optString("backdrop").takeIf { it.startsWith("http") },
                    logo = null,
                    description = null,
                    releaseInfo = item.optString("year").takeIf { it.isNotBlank() && it != "null" },
                    imdbRating = item.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                    genres = emptyList(),
                    imdbId = item.optString("imdb").takeIf { it.startsWith("tt") }
                )
            }
        )
    }

    companion object {
        const val BASE_URL = BuiltInCatalogs.BASE_PREFIX + "dexworld"
        const val ADDON_ID = "builtin.dexworld"
        private const val ROW_TYPE = "dexworld"
        private const val API = "https://blocks.dexworld.cc"
        private const val PAGE_SIZE = 40
        private const val PAGE_TTL_MS = 30L * 60 * 1000
        private const val ROW_TTL_MS = 6L * 60 * 60 * 1000

        /** Ids carry a colon ("b:dxk-net-174"), which routes and row keys do not take. */
        fun catalogIdOf(blockId: String) = blockId.replace(":", "~")
        fun blockIdOf(catalogId: String) = catalogId.replace("~", ":")
        fun collectionIdOf(kitId: String) = "dexworld_" + kitId.replace(":", "_")
    }
}

internal data class DexWorldPage(val kits: List<DexWorldKit>)
internal data class DexWorldKit(val id: String, val title: String, val members: List<DexWorldMember>)
internal data class DexWorldMember(val id: String, val title: String, val cover: String?)
internal data class DexWorldList(val page: Int, val pages: Int, val items: List<MetaPreview>)

@Module
@InstallIn(SingletonComponent::class)
abstract class DexWorldCatalogModule {
    @Binds
    @IntoSet
    abstract fun provider(impl: DexWorldCatalogProvider): BuiltInCatalogProvider
}
