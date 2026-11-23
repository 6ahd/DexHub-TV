package com.nuvio.tv.dexhub.sync

import android.util.Log
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSubtitleCueParser
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * An English subtitle of the same release as AutoSync's clock, ported from Dex Hub for Kodi (subsync/engref.py,
 * 5.10.601): when the video carries no subtitle to time by, an English subtitle made for this very release starts
 * its lines where people speak, on this release's own timing. OpenSubtitles' public Stremio add-on lists them with
 * their release names (no key). One is used only when it is surely of this release: its name matches the file's
 * closely (the same group and source), or two uploads agree with each other within 400 ms. Another cut of the film
 * (extended, director's) never counts.
 *
 * What leaves the device: the title's ids and, when the file can be read, its OpenSubtitles hash and size.
 */
internal object EnglishReference {
    private const val Tag = "DexHubSync"
    private const val Base = "https://opensubtitles-v3.strem.io"
    private const val MaxDownloads = 3
    private const val SameRelease = 60.0
    private const val AgreeMs = 400L
    private const val MinCues = 150

    data class Found(val cues: List<SubtitleSyncCue>, val name: String, val why: String)

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private var memo: Pair<String, Found?>? = null

    private fun list(playing: DexHubSyncReferences.Playing, hash: Pair<String, Long>?): List<JSONObject> {
        if (!playing.imdb.startsWith("tt")) return emptyList()
        val episodic = playing.season != null && playing.episode != null && playing.episode > 0
        val (kind, id) = if (episodic) "series" to "${playing.imdb}:${playing.season}:${playing.episode}" else "movie" to playing.imdb
        val extra = hash?.let { "/videoHash=${URLEncoder.encode(it.first, "UTF-8")}&videoSize=${it.second}" }.orEmpty()
        val url = "$Base/subtitles/$kind/$id$extra.json"
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val items = JSONObject(r.body?.string().orEmpty()).optJSONArray("subtitles") ?: return emptyList()
                (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { it.optString("lang").lowercase() in setOf("eng", "en") && it.optString("url").isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    private fun hearingImpaired(item: JSONObject): Boolean {
        val name = item.optString("subtitleFileName") + " " + item.optString("movieReleaseName")
        return Regex("""(?i)(^|[._\-\s\[(])(hi|sdh|cc)([._\-\s\])]|$)""").containsMatchIn(name) || item.optString("hi") == "1"
    }

    private suspend fun cues(url: String): List<SubtitleSyncCue> = runCatching {
        val body = AutomaticSubtitleSync.downloadSubtitleBody(url, emptyMap(), "en")
        if (body.length > 2 * 1024 * 1024) emptyList() else AutoSyncSubtitleCueParser.parse(body, url)
    }.getOrDefault(emptyList()).takeIf { it.size >= MinCues }.orEmpty()

    /** Whether two English subtitles timed the same release the same way: most starts one offset apart, near 0. */
    fun agree(a: List<SubtitleSyncCue>, b: List<SubtitleSyncCue>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val bs = b.map { it.startTimeMs }.sorted()
        val diffs = a.map { cue ->
            val s = cue.startTimeMs
            var i = bs.binarySearch(s).let { if (it < 0) -it - 1 else it }.coerceIn(0, bs.size - 1)
            if (i > 0 && abs(bs[i - 1] - s) < abs(bs[i] - s)) i--
            bs[i] - s
        }.filter { abs(it) <= 30_000 }.sorted()
        if (diffs.size < a.size * 0.6) return false
        val offset = diffs[diffs.size / 2]
        val matched = diffs.count { abs(it - offset) <= AgreeMs }.toDouble() / a.size
        return abs(offset) <= AgreeMs && matched >= 0.6
    }

    suspend fun find(playing: DexHubSyncReferences.Playing): Found? {
        memo?.let { (key, found) -> if (key == playing.streamUrl) return found }
        val hash = OpenSubtitlesHash.of(playing.streamUrl, playing.headers)
        val items = list(playing, hash)
        var out: Found? = null
        if (items.isNotEmpty()) {
            val video = ReleaseNames.best(playing.names)
            val cut = ReleaseNames.cut(playing.names.filterNotNull().joinToString(" "))
            val ranked = items.mapIndexedNotNull { i, x ->
                val name = x.optString("movieReleaseName").ifEmpty { x.optString("subtitleFileName") }
                if (ReleaseNames.cut(name) != cut) return@mapIndexedNotNull null
                val info = ReleaseNames.info(name)
                var score = if (video.hasHints) ReleaseNames.score(video, info) else 0.0
                // another streaming service's master (NF and AMZN) may differ: never this release on its name alone
                if (video.service.isNotEmpty() && info.service.isNotEmpty() && video.service != info.service) score = minOf(score, SameRelease - 1)
                if (hearingImpaired(x)) score -= 8
                Triple(score - i * 0.01, name, x.optString("url"))   // the add-on puts the very same file's first
            }.sortedByDescending { it.first }
            val got = ranked.take(MaxDownloads).mapNotNull { (score, name, url) -> cues(url).takeIf { it.isNotEmpty() }?.let { Triple(score, name, it) } }
            if (got.isNotEmpty() && got[0].first >= SameRelease) {
                out = Found(got[0].third, got[0].second, "name")
            } else {
                // two uploads that agree could both be of another release: with a known source, only its family
                val pool = if (video.hasHints && video.src.isNotEmpty()) got.filter { it.first >= 20 } else got
                loop@ for (i in pool.indices) for (j in i + 1 until pool.size) {
                    if (pool[i].second != pool[j].second && agree(pool[i].third, pool[j].third)) {
                        out = Found(pool[i].third, pool[i].second, "agree"); break@loop
                    }
                }
            }
            Log.i(Tag, "English reference: ${items.size} listed, ${got.size} read -> ${out?.let { "${it.name} (${it.why})" } ?: "none"}")
        }
        memo = playing.streamUrl to out
        return out
    }
}
