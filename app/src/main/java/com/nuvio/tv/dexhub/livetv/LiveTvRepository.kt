package com.nuvio.tv.dexhub.livetv

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dex Hub TV's Live TV (DEXHUB.md), from Flix: the viewer's playlists, kept on this device, and their channels and
 * guides, fetched from this device and kept a while (a lineup for [LineupFor], a guide for [GuideFor]) so the page
 * opens at once and a playlist is not fetched on every visit.
 */
@Singleton
class LiveTvRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("dexhub_live_tv", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "dexhub_live_tv").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _sources = MutableStateFlow(readSources())
    val sources: StateFlow<List<LiveSource>> = _sources

    private val guides = HashMap<String, Pair<Long, XmltvGuide>>()

    fun addM3u(name: String, url: String, guideUrl: String) {
        save(_sources.value + LiveSource(UUID.randomUUID().toString(), name.ifBlank { "M3U" }, LiveSource.Kind.M3U,
            url.trim(), guideUrl = guideUrl.trim()))
    }

    fun addXtream(name: String, server: String, username: String, password: String) {
        save(_sources.value + LiveSource(UUID.randomUUID().toString(), name.ifBlank { "Xtream" }, LiveSource.Kind.XTREAM,
            server.trim(), username.trim(), password.trim()))
    }

    fun remove(source: LiveSource) {
        save(_sources.value.filterNot { it.id == source.id })
        File(folder, "${source.id}.json").delete()
        synchronized(guides) { guides.remove(source.id) }
    }

    /** The source's channels: kept ones while fresh (or when the source cannot be reached), else fetched. */
    suspend fun lineup(source: LiveSource, refresh: Boolean = false): LiveLineup = withContext(Dispatchers.IO) {
        val file = File(folder, "${source.id}.json")
        val kept = runCatching { if (file.exists()) decode(file.readText()) else null }.getOrNull()
        if (kept != null && !refresh && System.currentTimeMillis() - file.lastModified() < LineupFor) return@withContext kept
        try {
            val fresh = fetch(source)
            if (fresh.channels.isEmpty()) throw LiveTvException("This playlist has no live channels")
            file.writeText(encode(fresh))
            fresh
        } catch (error: Exception) {
            kept ?: throw error
        }
    }

    /** The guide for the source, or null when it has none or it cannot be read. */
    suspend fun guide(source: LiveSource, lineup: LiveLineup): XmltvGuide? = withContext(Dispatchers.IO) {
        synchronized(guides) { guides[source.id] }?.let { (at, guide) ->
            if (System.currentTimeMillis() - at < GuideFor) return@withContext guide
        }
        val address = source.guideUrl.ifBlank { lineup.guideUrl.orEmpty() }.takeIf { it.startsWith("http") }
            ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(address).header("User-Agent", Agent).build()).execute().use { answer ->
                if (!answer.isSuccessful) throw LiveTvException("The guide answered ${answer.code}")
                XmltvGuide.read(answer.body!!.byteStream())
            }
        }.getOrNull()?.also { guide -> synchronized(guides) { guides[source.id] = System.currentTimeMillis() to guide } }
    }

    private fun fetch(source: LiveSource): LiveLineup = when (source.kind) {
        LiveSource.Kind.M3U -> M3uPlaylist.parse(get(source.url), source.url)
        LiveSource.Kind.XTREAM -> {
            val panel = XtreamPanel(source.url, source.username, source.password)
            val extension = panel.liveExtension(get(panel.accountUrl()))
            panel.lineup(get(panel.categoriesUrl()), get(panel.streamsUrl()), extension)
        }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", Agent).build()).execute().use { answer ->
            if (!answer.isSuccessful) throw LiveTvException("The address answered ${answer.code}")
            answer.body?.string() ?: throw LiveTvException("The address answered nothing")
        }

    private fun save(list: List<LiveSource>) {
        prefs.edit().putString(SourcesKey, JSONArray(list.map { source ->
            JSONObject().put("id", source.id).put("name", source.name).put("kind", source.kind.name)
                .put("url", source.url).put("username", source.username).put("password", source.password)
                .put("guideUrl", source.guideUrl)
        }).toString()).apply()
        _sources.value = list
    }

    private fun readSources(): List<LiveSource> = runCatching {
        val list = JSONArray(prefs.getString(SourcesKey, "[]"))
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            LiveSource(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                name = o.optString("name"),
                kind = runCatching { LiveSource.Kind.valueOf(o.optString("kind")) }.getOrDefault(LiveSource.Kind.M3U),
                url = o.optString("url"),
                username = o.optString("username"),
                password = o.optString("password"),
                guideUrl = o.optString("guideUrl")
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(lineup: LiveLineup): String = JSONObject()
        .put("guideUrl", lineup.guideUrl ?: "")
        .put("groups", JSONArray(lineup.groups))
        .put("channels", JSONArray(lineup.channels.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("number", c.number ?: 0).put("logo", c.logo ?: "")
                .put("group", c.group).put("epgId", c.epgId ?: "").put("url", c.url).put("headers", JSONObject(c.headers))
        })).toString()

    private fun decode(text: String): LiveLineup {
        val o = JSONObject(text)
        val groups = o.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getString(it) } }
        val channels = o.getJSONArray("channels").let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                val headers = c.optJSONObject("headers")
                LiveChannel(
                    id = c.getString("id"), name = c.getString("name"),
                    number = c.optInt("number").takeIf { it > 0 },
                    logo = c.optString("logo").ifEmpty { null },
                    group = c.getString("group"),
                    epgId = c.optString("epgId").ifEmpty { null },
                    url = c.getString("url"),
                    headers = headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty()
                )
            }
        }
        return LiveLineup(channels, groups, o.optString("guideUrl").ifEmpty { null })
    }

    companion object {
        private const val SourcesKey = "sources"
        private const val Agent = "DexHubTV"
        private const val LineupFor = 12 * 3_600_000L
        private const val GuideFor = 3 * 3_600_000L
    }
}
