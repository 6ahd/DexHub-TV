package com.nuvio.tv.dexhub.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * Shared subtitle sync with DexWorld (SYNC_SHARE_V1), ported from Dex Hub for Kodi (subsync/syncshare.py,
 * 5.10.601): a release timed once, on any device, is timed for everyone who plays it.
 *  - When the video has its own subtitle, its start times (never its text) go to DexWorld as this release's
 *    timeline, for devices that cannot read the file.
 *  - When it has none, DexWorld is asked first: a fix it trusts for this Arabic subtitle on this release, or the
 *    release's timeline. Either becomes AutoSync's reference.
 *
 * What leaves the device: the release's keys (the OpenSubtitles hash and size, a hash of its embedded subtitle's
 * starts, its release-like file name, the title's ids) and the subtitle's keys (hashes of its text and of its first
 * starts). Never a link, a path, an account or a subtitle's text. Everything fails quietly and is not asked again
 * for a while when the server does not answer.
 */
internal object DexWorldSync {
    private const val Tag = "DexHubSync"
    private const val Base = "https://dexworld.cc/subtitles/sync/v1"
    private const val Prefs = "dexhub_syncshare"
    const val Intro = "هذهالترجمةمنDexSubs"

    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var downUntil = 0L
    private val sentTimelines = HashSet<String>()
    private val enabledState = MutableStateFlow<Boolean?>(null)

    // ---------------------------------------------------------------- the switch

    fun enabledFlow(context: Context): StateFlow<Boolean> {
        if (enabledState.value == null) enabledState.value = prefs(context).getBoolean("on", true)
        @Suppress("UNCHECKED_CAST")
        return enabledState.asStateFlow() as StateFlow<Boolean>
    }

    fun enabled(context: Context) = enabledFlow(context).value

    fun setEnabled(context: Context, on: Boolean) {
        enabledState.value = on
        prefs(context).edit().putBoolean("on", on).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- the calls

    private fun device(context: Context): String = prefs(context).getString("device", null)
        ?: UUID.randomUUID().toString().also { prefs(context).edit().putString("device", it).apply() }

    private fun call(context: Context, path: String, body: JSONObject?, auth: Boolean = true): JSONObject? {
        if (System.currentTimeMillis() < downUntil) return null
        val request = Request.Builder().url(Base + path).header("Accept", "application/json")
        if (auth) {
            var token = prefs(context).getString("token", null)
            if (token == null) {
                token = call(context, "/hello", JSONObject().put("device", device(context)), auth = false)?.optString("token")?.takeIf { it.isNotEmpty() }
                    ?: return null
                prefs(context).edit().putString("token", token).apply()
            }
            request.header("X-Dex-Device", "${device(context)}.$token")
        }
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val (status, text) = try {
            http.newCall(request.build()).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) {
            downUntil = System.currentTimeMillis() + 30 * 60_000L
            Log.i(Tag, "sync share: $path not answered; not asked again for 30 min")
            return null
        }
        when {
            status in setOf(404, 501) -> { downUntil = System.currentTimeMillis() + 6 * 3_600_000L; return null }
            status in 502..504 -> { downUntil = System.currentTimeMillis() + 15 * 60_000L; return null }
            (status == 401 || status == 403) && auth -> { prefs(context).edit().remove("token").apply(); return null }
            status != 200 -> return null
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ---------------------------------------------------------------- the keys

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A hash of the embedded subtitle's first 200 starts: the same release, the same key. */
    fun cuesKey(starts: List<Long>): String {
        val first = starts.sorted().take(200)
        return if (first.size < 30) "" else sha1(first.joinToString(",")).take(40)
    }

    private val releaseTag = Regex("""(2160p|1080p|720p|480p|\b4k\b|\buhd\b|bluray|blu-ray|bdrip|brrip|remux|web-?dl|webrip|hdtv|dvdrip|x26[45]|h\.?26[45]|hevc|\bav1\b|s\d{1,2}e\d{1,3})""", RegexOption.IGNORE_CASE)
    private val tokenish = Regex("""[A-Za-z0-9_-]{32,}""")

    /** The release's file name, normalised, when it looks like a release. */
    fun nameKey(filename: String): String {
        val name = filename.substringBefore('|').substringBefore('?').substringBefore('#')
            .replace('\\', '/').trimEnd('/').substringAfterLast('/').trim().take(200)
        if (name.length < 8 || !releaseTag.containsMatchIn(name) || tokenish.containsMatchIn(name)) return ""
        return name.lowercase().replace(Regex("""\.(mkv|mp4|avi|m2ts|ts|mov|webm)$"""), "")
            .replace(Regex("""[\s_\-\[\]()]+"""), ".").trim('.')
    }

    fun videoKeys(playing: DexHubSyncReferences.Playing, refStarts: List<Long>? = null): JSONObject {
        val keys = JSONObject()
        OpenSubtitlesHash.of(playing.streamUrl, playing.headers)?.let { (oh, size) -> keys.put("oh", oh).put("size", size) }
        refStarts?.let { cuesKey(it) }?.takeIf { it.isNotEmpty() }?.let { keys.put("cf", it) }
        val fn = playing.names.firstNotNullOfOrNull { n -> n?.let(::nameKey)?.takeIf { it.isNotEmpty() } }
        if (fn != null) keys.put("fn", fn)
        if (playing.imdb.isNotEmpty()) keys.put("imdb", playing.imdb)
        playing.season?.takeIf { it > 0 }?.let { keys.put("s", it.toString()) }
        playing.episode?.takeIf { it > 0 }?.let { keys.put("e", it.toString()) }
        return keys
    }

    private val tags = Regex("""<[^>]*>|\{[^}]*\}""")
    private val bidi = Regex("[‎‏‪-‮⁦-⁩؜﻿]")
    private val space = Regex("""\s+""")
    private val pair = Regex("""((?:\d{1,3}:)?\d{1,2}:\d{2}[,.]\d{1,3})[ \t]*-->[ \t]*((?:\d{1,3}:)?\d{1,2}:\d{2}[,.]\d{1,3})""")
    private val assLine = Regex("""(?m)^(Dialogue|Comment):[ \t]*[^,\r\n]*,(\d{1,2}:\d{2}:\d{2}[.:]\d{1,3}),(\d{1,2}:\d{2}:\d{2}[.:]\d{1,3}),""")

    fun timeMs(t: String): Long {
        val s = t.replace(',', '.')
        var main = s.substringBeforeLast('.', "")
        var frac = s.substringAfterLast('.', "")
        if (main.isEmpty()) {                       // an old ASS H:MM:SS:cc
            val parts = s.split(':')
            main = parts.take(3).joinToString(":"); frac = parts.getOrElse(3) { "0" }
        }
        val parts = main.split(':').map { it.toLong() }.toMutableList()
        while (parts.size < 3) parts.add(0, 0)
        val (h, m, sec) = parts.takeLast(3)
        val ms = if (frac.isNotEmpty()) (frac + "000").take(3).toLong() else 0
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    private fun introText(chunk: String) = space.replace(tags.replace(chunk.replace("\\N", " ").replace("\\n", " "), ""), "")

    /** Every line's start in file order, DexWorld's intro line left out. */
    fun fileStarts(raw: String): List<Long> {
        val text = raw.trimStart('﻿')
        if ("[Events]" in text) {
            return assLine.findAll(text).mapNotNull { m ->
                val body = text.substring(m.range.last + 1).substringBefore('\n').split(',', limit = 7).last()
                if (introText(body) == Intro) null else runCatching { timeMs(m.groupValues[2]) }.getOrNull()
            }.toList()
        }
        return pair.findAll(text).mapNotNull { m ->
            val rest = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 401)).replace("\r", "")
            val body = rest.removePrefix("\n").substringBefore("\n\n")
            if (introText(body) == Intro) null else runCatching { timeMs(m.groupValues[1]) }.getOrNull()
        }.toList()
    }

    private fun textLines(raw: String): List<String> {
        val text = raw.trimStart('﻿')
        if ("[Events]" in text) {
            return text.lines().filter { it.startsWith("Dialogue:") }
                .flatMap { it.split(',', limit = 10).last().split(Regex("""\\[Nn]""")) }
        }
        return text.lines().filter { "-->" !in it && !it.trimStart().startsWith("WEBVTT") }
    }

    /** (sh, tf) as DexWorld makes them: the text's hash and the first 120 starts' hash; "" when too short. */
    fun subKeys(raw: String): Pair<String, String> {
        val keep = textLines(raw).map { space.replace(bidi.replace(tags.replace(it, ""), ""), "") }
            .filter { it.isNotEmpty() && !it.all(Char::isDigit) && it != Intro }
        val sh = if (keep.size >= 20) sha1(keep.joinToString("\n")) else ""
        val starts = fileStarts(raw).take(120)
        val tf = if (starts.size >= 30) sha1(starts.joinToString(",")).take(16) else ""
        return sh to tf
    }

    private val arabic = Regex("[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
    private val latin = Regex("[A-Za-z]")

    /** DexWorld shares Arabic fixes only. */
    fun isArabic(raw: String): Boolean {
        var text = tags.replace(raw.take(60000), " ")
        if ("[Events]" in text) text = text.substringAfter("[Events]")
        val ar = arabic.findAll(text).count()
        val la = latin.findAll(text).count()
        return ar >= 200 && ar >= 0.25 * (ar + la)
    }

    // ---------------------------------------------------------------- the fix as a reference

    /** The subtitle's own lines as a trusted fix moves them: AutoSync, timing the subtitle by these, finds that fix. */
    fun applyFix(cues: List<SubtitleSyncCue>, fix: JSONObject): List<SubtitleSyncCue> {
        val scale = fix.optDouble("scale", 1.0).takeIf { it > 0 } ?: 1.0
        val offset = fix.optLong("offset_ms")
        val pieces = fix.optJSONArray("pieces")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optLong("from_ms") to it.optLong("offset_ms") }
        }?.sortedBy { it.first }.orEmpty()
        val lines = fix.optString("lines").takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching {
                val packed = Base64.decode(encoded, Base64.DEFAULT)
                val inflater = Inflater().apply { setInput(packed) }
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) { val n = inflater.inflate(buf); if (n == 0 && inflater.needsInput()) break; out.write(buf, 0, n) }
                inflater.end()
                val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                List(b.remaining() / 2) { b.short * 10L }
            }.getOrNull()
        }?.takeIf { it.size == cues.size }
        fun map(a: Long): Long {
            val shift = if (pieces.isNotEmpty()) pieces.lastOrNull { it.first <= a }?.second ?: pieces.first().second else offset
            return (a * scale).toLong() + shift
        }
        return cues.mapIndexed { i, c ->
            val d = lines?.get(i) ?: 0L
            c.copy(startTimeMs = map(c.startTimeMs) + d, endTimeMs = map(c.endTimeMs) + d)
        }.sortedBy { it.startTimeMs }
    }

    // ---------------------------------------------------------------- what AutoSync asks

    /** A reference from DexWorld for this Arabic subtitle on this release, or null. */
    fun reference(context: Context, playing: DexHubSyncReferences.Playing, raw: String?, cues: List<SubtitleSyncCue>): Pair<List<SubtitleSyncCue>, String>? {
        if (!enabled(context) || raw.isNullOrEmpty() || !isArabic(raw)) return null
        val (sh, tf) = subKeys(raw)
        if (sh.isEmpty() || tf.isEmpty()) return null
        val video = videoKeys(playing)
        if (video.length() == 0) return null
        val got = call(context, "/lookup", JSONObject().put("video", video)
            .put("subs", JSONArray().put(JSONObject().put("sh", sh).put("tf", tf)))) ?: return null
        val fixes = got.optJSONObject("fixes")
        val item = fixes?.optJSONObject("$sh:$tf")
        if (item != null && item.optString("trust") in setOf("consensus", "strong")) {
            val fix = item.optJSONObject("fix") ?: item
            Log.i(Tag, "sync share: a shared fix (${fix.optString("mode")}) for release ${got.optString("release")}")
            return applyFix(cues, fix) to "fix"
        }
        val release = got.optString("release")
        if (got.optBoolean("timeline") && release.isNotEmpty()) {
            val timeline = call(context, "/timeline/$release", null) ?: return null
            val starts = timeline.optJSONArray("starts") ?: return null
            val list = (0 until starts.length()).map { starts.optLong(it) }.sorted()
            if (list.size < 30) return null
            Log.i(Tag, "sync share: release $release's shared reference of ${list.size} lines")
            return list.mapIndexed { i, s ->
                val next = list.getOrNull(i + 1) ?: (s + 2500)
                SubtitleSyncCue(s, minOf(s + 2500, maxOf(s + 300, next - 100)), "")
            } to "timeline"
        }
        return null
    }

    /** This release's reference times (the embedded subtitle's starts, never its text), once a run. */
    fun sendTimeline(context: Context, playing: DexHubSyncReferences.Playing, starts: List<Long>) {
        if (!enabled(context) || starts.size < 10) return
        scope.launch {
            val video = videoKeys(playing, starts)
            val key = video.optString("oh").ifEmpty { video.optString("cf") }
            if (key.isEmpty() || !synchronized(sentTimelines) { sentTimelines.add(key) }) return@launch
            call(context, "/timeline", JSONObject().put("video", video).put("kind", "cues")
                .put("starts", JSONArray(starts.sorted().take(6000))))
        }
    }
}
