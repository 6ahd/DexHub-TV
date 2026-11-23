package com.nuvio.tv.dexhub.usenet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Usenet in the sources (DEXHUB.md): a small Stremio add-on on 127.0.0.1 that the app installs for itself once a
 * provider and an indexer are set up. Its streams come from [UsenetSearch], so they sit in the sources list with
 * the others (and in Flix's design); picking one asks /play, which opens the release in [UsenetEngine] and sends
 * the player on to the engine's own stream. Seeking asks /play again and gets the same stream.
 *
 * Nothing in a stream's address names the indexer or its key: /play/<token> is looked up on this device.
 */
object UsenetAddon {
    private const val Tag = "DexHubUsenet"
    const val Port = 17465
    const val ManifestUrl = "http://127.0.0.1:$Port/manifest.json"
    private const val PlaysKept = 400

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun addonRepository(): AddonRepository
    }

    private data class Play(val link: String, val title: String, val season: Int?, val episode: Int?)

    private lateinit var app: Context
    private var server: Server? = null
    private val plays = LinkedHashMap<String, Play>()
    private val opened = ConcurrentHashMap<String, String>()
    private val opening = ConcurrentHashMap<String, Any>()
    private val targets = ConcurrentHashMap<String, UsenetSearch.Target>()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    /** From the app's start: serves the add-on on this device. The engine itself starts only when it is needed. */
    fun start(context: Context) {
        app = context.applicationContext
        Thread({
            runCatching { readPlays() }
            runCatching {
                server = Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            }.onFailure { Log.w(Tag, "the add-on did not start", it) }
        }, "DexHubUsenetAddon").start()
    }

    /** After the setup was saved: the add-on is installed (once) when Usenet is ready and the engine is here. */
    suspend fun install(context: Context): Boolean {
        if (!UsenetConfig.current(context).ready || !UsenetEngine.available()) return false
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        deps.addonRepository().addAddon(ManifestUrl)
        return true
    }

    // ---------------------------------------------------------------- what is searched for

    private fun tmdb(path: String, extra: Map<String, String> = emptyMap()): JSONObject? {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return null
        val url = "https://api.themoviedb.org/3/$path".toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("api_key", key)
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: return null
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null
            }
        }.getOrNull()
    }

    /** A Stremio id ("tt0111161", "tt0944947:1:2", "tmdb:1399:1:2") as what the indexers are asked. */
    private fun target(type: String, id: String): UsenetSearch.Target? {
        val parts = id.split(':')
        val series = type == "series"
        val (base, rest) = if (parts.firstOrNull() == "tmdb") "tmdb:" + parts.getOrElse(1) { "" } to parts.drop(2) else parts[0] to parts.drop(1)
        val season = if (series) rest.getOrNull(0)?.toIntOrNull() else null
        val episode = if (series) rest.getOrNull(1)?.toIntOrNull() else null
        val known = targets.getOrPut("$type|$base") {
            var imdb = base.takeIf { it.startsWith("tt") }.orEmpty()
            var tvdb = ""
            var title = ""
            var year = ""
            if (base.startsWith("tmdb:")) {
                val details = tmdb("${if (series) "tv" else "movie"}/${base.removePrefix("tmdb:")}", mapOf("append_to_response" to "external_ids"))
                if (details != null) {
                    val ids = details.optJSONObject("external_ids")
                    imdb = details.optString("imdb_id").ifEmpty { ids?.optString("imdb_id").orEmpty() }
                    tvdb = ids?.optInt("tvdb_id")?.takeIf { it > 0 }?.toString().orEmpty()
                    title = details.optString(if (series) "name" else "title")
                    year = details.optString(if (series) "first_air_date" else "release_date").take(4)
                }
            } else if (imdb.isNotEmpty()) {
                val found = tmdb("find/$imdb", mapOf("external_source" to "imdb_id"))
                val hit = found?.optJSONArray(if (series) "tv_results" else "movie_results")?.optJSONObject(0)
                if (hit != null) {
                    title = hit.optString(if (series) "name" else "title")
                    year = hit.optString(if (series) "first_air_date" else "release_date").take(4)
                    if (series) tvdb = tmdb("tv/${hit.optInt("id")}/external_ids")?.optInt("tvdb_id")?.takeIf { it > 0 }?.toString().orEmpty()
                }
            }
            if (imdb.isEmpty() && title.isEmpty()) return null
            UsenetSearch.Target(imdb = imdb, tvdb = tvdb, title = title.takeIf { t -> t.any { it.isLetterOrDigit() && it.code < 128 } }.orEmpty(), year = year)
        }
        if (series && (season == null || episode == null)) return null
        return known.copy(season = season, episode = episode)
    }

    // ---------------------------------------------------------------- the plays

    private fun playsFile() = File(app.filesDir, "usenet/plays.json")

    private fun readPlays() {
        val raw = playsFile().takeIf { it.exists() }?.readText() ?: return
        val o = JSONObject(raw)
        synchronized(plays) {
            o.keys().forEach { k ->
                val p = o.optJSONObject(k) ?: return@forEach
                plays[k] = Play(p.optString("link"), p.optString("title"),
                    p.optInt("season", -1).takeIf { it >= 0 }, p.optInt("episode", -1).takeIf { it >= 0 })
            }
        }
    }

    private fun writePlays() {
        val o = JSONObject()
        synchronized(plays) {
            plays.forEach { (k, p) ->
                o.put(k, JSONObject().put("link", p.link).put("title", p.title)
                    .put("season", p.season ?: -1).put("episode", p.episode ?: -1))
            }
        }
        runCatching { playsFile().apply { parentFile?.mkdirs() }.writeText(o.toString()) }
    }

    private fun remember(play: Play): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${play.link}|${play.season}|${play.episode}".toByteArray())
        val token = digest.take(10).joinToString("") { "%02x".format(it) }
        synchronized(plays) {
            plays.remove(token)
            plays[token] = play
            while (plays.size > PlaysKept) plays.remove(plays.keys.first())
        }
        return token
    }

    // ---------------------------------------------------------------- the add-on's answers

    private fun manifest() = JSONObject()
        .put("id", "dexhub.usenet")
        .put("version", "1.0.0")
        .put("name", "Dex Hub Usenet")
        .put("description", "Usenet through Dex Hub's built-in engine, with your own providers and indexers.")
        .put("resources", JSONArray(listOf("stream")))
        .put("types", JSONArray(listOf("movie", "series")))
        .put("idPrefixes", JSONArray(listOf("tt", "tmdb:")))
        .put("catalogs", JSONArray())

    private fun streams(type: String, id: String): JSONObject {
        val setup = UsenetConfig.current(app)
        val empty = JSONObject().put("streams", JSONArray())
        if (!setup.ready || !UsenetEngine.available()) return empty
        val target = target(type, id) ?: return empty
        val releases = runBlocking {
            UsenetSearch.search(setup, target) { refused -> say(app.getString(R.string.dexhub_usenet_refused, refused.indexer, refused.why.take(100))) }
        }
        if (releases.isNotEmpty()) UsenetEngine.warm(app)
        val protectedLabel = app.getString(R.string.dexhub_usenet_password)
        val list = JSONArray()
        for (r in releases) {
            val token = remember(Play(r.link, r.title, target.season, target.episode))
            val facts = listOf(UsenetSearch.sizeLabel(r.size), r.age, r.indexer, if (r.password) protectedLabel else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            val hints = JSONObject().put("bingeGroup", "dexhub-usenet-${r.quality}").put("filename", r.title).put("notWebReady", true)
            if (r.size > 0) hints.put("videoSize", r.size)
            list.put(JSONObject()
                .put("name", "Usenet\n${r.quality}")
                .put("title", "${r.title}\n$facts")
                .put("description", "${r.title}\n$facts")
                .put("url", "http://127.0.0.1:$Port/play/$token/" + URLEncoder.encode(r.title, "UTF-8").replace("+", "%20"))
                .put("behaviorHints", hints))
        }
        writePlays()
        return JSONObject().put("streams", list)
    }

    /** The engine's stream for a picked release, opened once and given again while it plays. */
    private fun resolve(token: String): String {
        opened[token]?.let { if (UsenetEngine.serves(it)) return it }
        val lock = opening.getOrPut(token) { Any() }
        synchronized(lock) {
            opened[token]?.let { if (UsenetEngine.serves(it)) return it }
            val play = synchronized(plays) { plays[token] } ?: throw UsenetEngine.UsenetException("this release is no longer known; open its sources again")
            val setup = UsenetConfig.current(app)
            val stream = UsenetEngine.open(app, play.link, UsenetConfig.servers(setup), play.season, play.episode, setup.privateNetwork)
            opened.clear()
            opened[token] = stream.url
            return stream.url
        }
    }

    private fun say(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private class Code(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }

    private class Server : NanoHTTPD("127.0.0.1", Port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return try {
                when {
                    uri == "/manifest.json" -> json(manifest())
                    uri.startsWith("/stream/") -> {
                        val rest = uri.removePrefix("/stream/").removeSuffix(".json")
                        val type = rest.substringBefore('/')
                        val id = URLDecoder.decode(rest.substringAfter('/'), "UTF-8")
                        json(streams(type, id))
                    }
                    uri.startsWith("/play/") -> {
                        val token = uri.removePrefix("/play/").substringBefore('/')
                        val url = resolve(token)
                        newFixedLengthResponse(Code(307, "Temporary Redirect"), MIME_PLAINTEXT, "").apply { addHeader("Location", url) }
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            } catch (e: UsenetEngine.UsenetException) {
                Log.w(Tag, "the release did not open (${e.kind}): ${e.message}")
                say(app.getString(when (e.kind) {
                    "provider" -> R.string.dexhub_usenet_provider_refused
                    "engine" -> R.string.dexhub_usenet_no_engine
                    else -> R.string.dexhub_usenet_not_opened
                }))
                newFixedLengthResponse(Code(502, "Bad Gateway"), MIME_PLAINTEXT, e.message.orEmpty())
            } catch (e: Exception) {
                Log.w(Tag, "request failed: $uri", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message.orEmpty())
            }
        }

        private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())
            .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }
}
