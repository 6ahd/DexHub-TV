package com.nuvio.tv.dexhub.usenet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.w3c.dom.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Usenet search, ported from Dex Hub for Kodi (resources/lib/usenet_source.py): every indexer at once, by IMDb or
 * TVDB id first and by name when the id finds nothing; a Prowlarr searches all of its Usenet indexers in one call.
 * The same search is not asked again within half an hour (most indexers allow about 100 calls a day).
 */
object UsenetSearch {
    data class Target(
        val imdb: String = "",
        val tvdb: String = "",
        val title: String = "",
        val year: String = "",
        val season: Int? = null,
        val episode: Int? = null
    ) {
        val episodic: Boolean get() = season != null && episode != null
    }

    data class Release(
        val title: String,
        val link: String,
        val size: Long,
        val age: String,
        val indexer: String,
        val password: Boolean
    ) {
        val quality: String get() = qualityOf(title)
    }

    private const val CacheForMs = 30 * 60_000L
    private const val BudgetMs = 25_000L
    private const val Agent = "Dex Hub TV"
    private val resolution = Regex("""(?<![a-z0-9])(2160p|4k|uhd|1080p|720p|576p|480p)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    class Refused(val indexer: String, val why: String) : Exception("$indexer: $why")

    fun qualityOf(title: String): String {
        val found = resolution.find(title.replace('.', ' ').replace('_', ' ')) ?: return "SD"
        val text = found.groupValues[1].uppercase(Locale.ROOT)
        return if (text == "4K" || text == "UHD" || text == "2160P") "2160p" else text.lowercase(Locale.ROOT)
    }

    fun sizeLabel(size: Long): String = when {
        size >= 1L shl 30 -> "%.2f GB".format(Locale.ROOT, size / 1073741824.0)
        size > 0 -> "${size shr 20} MB"
        else -> ""
    }

    // ---------------------------------------------------------------- asking an indexer

    /** Each call gets only the time left of the search's 25 seconds, so a late name search ends with the rest. */
    private class Ask(val fresh: Boolean, val deadline: Long)

    private fun get(url: String, headers: Map<String, String> = emptyMap(), ask: Ask): String {
        if (!ask.fresh) cache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < CacheForMs) return body }
        val left = ask.deadline - System.currentTimeMillis()
        if (left < 2000) throw java.io.InterruptedIOException("out of time")
        val client = http.newBuilder().callTimeout(minOf(20_000L, left), TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(url).header("User-Agent", Agent).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val body = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Refused("", refusal(text).ifEmpty { "HTTP ${response.code}" })
            text
        }
        if (refusal(body).isEmpty()) {                 // a refusal (a daily limit) is asked again next time
            val now = System.currentTimeMillis()
            cache.entries.removeIf { now - it.value.first >= CacheForMs }
            cache[url] = now to body
        }
        return body
    }

    private fun xml(raw: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(raw.byteInputStream()).documentElement
    }.getOrNull()

    /** The indexer's own words when it refused (Newznab <error description=...>), else "". */
    fun refusal(raw: String): String {
        if (!raw.trimStart().startsWith("<")) return ""
        val root = xml(raw) ?: return ""
        return if (root.tagName == "error") root.getAttribute("description").ifEmpty { root.getAttribute("code").ifEmpty { "error" } } else ""
    }

    private val dates = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz")

    private fun age(stamp: String): String {
        for (pattern in dates) {
            val time = runCatching { SimpleDateFormat(pattern, Locale.US).parse(stamp.trim())?.time }.getOrNull() ?: continue
            val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt().coerceAtLeast(0)
            return if (days < 365) "${days}d" else "${days / 365}y"
        }
        return ""
    }

    /** A Newznab answer as releases. */
    fun newznabReleases(raw: String, indexer: String): List<Release> {
        val root = xml(raw) ?: return emptyList()
        val items = root.getElementsByTagName("item")
        val out = ArrayList<Release>()
        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            var title = ""
            var link = ""
            var enclosureUrl = ""
            var enclosureLength = 0L
            var pubDate = ""
            val attrs = HashMap<String, String>()
            val children = item.childNodes
            for (k in 0 until children.length) {
                val child = children.item(k) as? Element ?: continue
                val tag = child.tagName.substringAfter(':')
                when {
                    tag == "title" -> title = child.textContent.trim()
                    tag == "link" -> link = child.textContent.trim()
                    tag == "pubDate" -> pubDate = child.textContent.trim()
                    tag == "enclosure" -> {
                        enclosureUrl = child.getAttribute("url")
                        enclosureLength = child.getAttribute("length").toLongOrNull() ?: 0L
                    }
                    tag == "attr" && child.getAttribute("name").isNotEmpty() -> attrs[child.getAttribute("name")] = child.getAttribute("value")
                }
            }
            val url = enclosureUrl.ifEmpty { link }.trim()
            if (title.isEmpty() || !url.startsWith("http")) continue
            val size = enclosureLength.takeIf { it > 0 } ?: attrs["size"]?.toLongOrNull() ?: 0L
            out += Release(
                title = title,
                link = url,
                size = size,
                age = age(attrs["usenetdate"] ?: pubDate),
                indexer = indexer,
                password = attrs["password"].let { it != null && it != "" && it != "0" }
            )
        }
        return out
    }

    /** Prowlarr's search answer as releases, each with the name of the indexer it came from. */
    fun prowlarrReleases(raw: String, fallback: String): List<Release> {
        val rows = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Release>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("protocol") != "usenet") continue
            val title = row.optString("title").trim()
            val link = row.optString("downloadUrl").trim()
            if (title.isEmpty() || !link.startsWith("http")) continue
            val days = row.optInt("age")
            out += Release(
                title = title,
                link = link,
                size = row.optLong("size"),
                age = if (row.has("publishDate")) (if (days < 365) "${days}d" else "${days / 365}y") else "",
                indexer = row.optString("indexer").ifEmpty { fallback },
                password = false
            )
        }
        return out
    }

    private fun newznabCalls(t: Target): List<Map<String, String>> = buildList {
        val imdb = t.imdb.filter { it.isDigit() }
        if (t.episodic) {
            if (t.tvdb.isNotEmpty()) add(mapOf("t" to "tvsearch", "tvdbid" to t.tvdb, "season" to "${t.season}", "ep" to "${t.episode}"))
            if (imdb.isNotEmpty()) add(mapOf("t" to "tvsearch", "imdbid" to imdb, "season" to "${t.season}", "ep" to "${t.episode}"))
            if (t.title.isNotEmpty()) add(mapOf("t" to "search", "cat" to "5000", "q" to "%s S%02dE%02d".format(t.title, t.season, t.episode)))
        } else {
            if (imdb.isNotEmpty()) add(mapOf("t" to "movie", "imdbid" to imdb))
            if (t.title.isNotEmpty()) add(mapOf("t" to "search", "cat" to "2000", "q" to "${t.title} ${t.year}".trim()))
        }
    }

    private fun searchNewznab(indexer: UsenetConfig.Indexer, t: Target, ask: Ask): List<Release> {
        val name = indexer.name.ifBlank { indexer.url.toHttpUrlOrNull()?.host ?: "indexer" }
        var base = indexer.url.trim().trimEnd('/')
        if (!base.endsWith("/api")) base += "/api"
        for (params in newznabCalls(t)) {
            val url = base.toHttpUrlOrNull()?.newBuilder()?.apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                addQueryParameter("limit", "100")
                addQueryParameter("apikey", indexer.key.trim())
            }?.build()?.toString() ?: return emptyList()
            val raw = try { get(url, ask = ask) } catch (e: Refused) { throw Refused(name, e.why) }
            refusal(raw).takeIf { it.isNotEmpty() }?.let { throw Refused(name, it) }   // some refuse with a 200
            val found = newznabReleases(raw, name)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun searchProwlarr(indexer: UsenetConfig.Indexer, t: Target, ask: Ask): List<Release> {
        val name = indexer.name.ifBlank { "Prowlarr" }
        val base = indexer.url.trim().trimEnd('/')
        val headers = mapOf("X-Api-Key" to indexer.key.trim(), "Accept" to "application/json")
        val list = try { get("$base/api/v1/indexer", headers, ask) } catch (e: Refused) { throw Refused(name, e.why) }
        val rows = runCatching { JSONArray(list) }.getOrNull() ?: return emptyList()
        val ids = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("protocol") == "usenet" && it.optBoolean("enable", true) && it.has("id") }
            .map { it.optInt("id") }
        if (ids.isEmpty()) return emptyList()
        val imdb = t.imdb.filter { it.isDigit() }
        val calls = buildList {
            if (t.episodic) {
                val tokens = "{Season:${t.season}}{Episode:${t.episode}}"
                if (imdb.isNotEmpty()) add(Triple("tvsearch", "{ImdbId:tt$imdb}$tokens", "5000"))
                if (t.tvdb.isNotEmpty()) add(Triple("tvsearch", "{TvdbId:${t.tvdb}}$tokens", "5000"))
                if (t.title.isNotEmpty()) add(Triple("search", "%s S%02dE%02d".format(t.title, t.season, t.episode), "5000"))
            } else {
                if (imdb.isNotEmpty()) add(Triple("movie", "{ImdbId:tt$imdb}", "2000"))
                if (t.title.isNotEmpty()) add(Triple("search", "${t.title} ${t.year}".trim(), "2000"))
            }
        }
        for ((type, query, category) in calls) {
            val url = "$base/api/v1/search".toHttpUrlOrNull()?.newBuilder()?.apply {
                addQueryParameter("type", type)
                addQueryParameter("query", query)
                addQueryParameter("categories", category)
                addQueryParameter("limit", "100")
                ids.forEach { addQueryParameter("indexerIds", it.toString()) }
            }?.build()?.toString() ?: return emptyList()
            val raw = try { get(url, headers, ask) } catch (e: Refused) { throw Refused(name, e.why) }
            val found = prowlarrReleases(raw, name)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Every switched-on indexer's releases for [target], within 25 seconds, one of each (by name and size).
     * [refused] hears an indexer that said no (a daily limit, a wrong key).
     */
    suspend fun search(
        setup: UsenetConfig.Setup,
        target: Target,
        fresh: Boolean = false,
        refused: (Refused) -> Unit = {}
    ): List<Release> = withContext(Dispatchers.IO) {
        val ask = Ask(fresh, System.currentTimeMillis() + BudgetMs)
        val found = coroutineScope {
            setup.indexers.filterNot { it.off }.map { indexer ->
                async {
                    try {
                        if (indexer.prowlarr) searchProwlarr(indexer, target, ask) else searchNewznab(indexer, target, ask)
                    } catch (e: Refused) {
                        refused(e); emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        val seen = HashSet<Pair<String, Long>>()
        found.filter { seen.add(it.title.lowercase(Locale.ROOT) to it.size / 10_485_760) }
            .sortedWith(compareByDescending<Release> { rank(it.quality) }.thenByDescending { it.size })
    }

    private fun rank(quality: String) = when (quality) {
        "2160p" -> 5; "1080p" -> 4; "720p" -> 3; "576p" -> 2; "480p" -> 1; else -> 0
    }
}
