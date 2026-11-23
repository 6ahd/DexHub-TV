package com.nuvio.tv.dexhub.usenet

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The Usenet engine inside the app (DEXHUB.md), the same engine Dex Hub for Kodi runs (script.dexhub.usenet:
 * NuvioTV's engine, built as an Android library, GPL-3.0). It is a small streaming server on 127.0.0.1: given an NZB
 * and the viewer's Usenet providers it opens the release (RAR, 7z and disc images included) and serves its film as a
 * plain HTTP stream, fetching articles as the player reads.
 *
 * The library is packed by the build (dexhub-build.yml) and reached through app/src/main/cpp/dexhub_usenet_jni.c.
 * A build without it has no engine: [available] is false and Usenet stays out of the sources.
 */
object UsenetEngine {
    private const val Tag = "DexHubUsenet"
    private const val MemoryMiB = 256

    class UsenetException(message: String, val kind: String = "") : Exception(message)

    data class Opened(val url: String, val id: String, val filename: String, val size: Long)

    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeStart(json: String): Int
    @JvmStatic private external fun nativePort(): Int
    @JvmStatic private external fun nativeStop()

    private var loaded: Boolean? = null
    private var port = 0
    private var token = ""
    private var session = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Whether the engine is in this build and loads on this device. */
    @Synchronized
    fun available(): Boolean = loaded ?: runCatching {
        System.loadLibrary("nuviousenet")
        System.loadLibrary("dexhub_usenet")
        nativeAvailable()
    }.onFailure { Log.i(Tag, "no engine in this build: ${it.message}") }.getOrDefault(false).also { loaded = it }

    private fun healthy(): Boolean = port > 0 && runCatching {
        val request = Request.Builder().url("http://127.0.0.1:$port/health").header("Authorization", "Bearer $token").build()
        http.newBuilder().readTimeout(3, TimeUnit.SECONDS).build().newCall(request).execute().use { it.code == 200 }
    }.getOrDefault(false)

    /** (port, token) of the running engine, started when needed. */
    @Synchronized
    private fun ensure(context: Context): Pair<Int, String> {
        if (!available()) throw UsenetException("the Usenet engine is not in this build", "engine")
        if (port > 0 && nativePort() == port && healthy()) return port to token
        if (port > 0) runCatching { nativeStop() }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        token = bytes.joinToString("") { "%02x".format(it) }
        val cache = File(context.filesDir, "usenet/nzb-cache").apply { mkdirs() }
        val start = JSONObject()
            .put("token", token)
            .put("certificates", JSONArray())
            .put("memoryMiB", MemoryMiB)
            .put("nzbCacheDir", cache.absolutePath)
        val got = nativeStart(start.toString())
        if (got <= 0) {
            port = 0
            throw UsenetException("the Usenet engine did not start ($got)", "engine")
        }
        port = got
        session = ""
        Log.i(Tag, "started on port $port")
        return port to token
    }

    /** Starts the engine now so its connections are open by the time a release is picked. Waits for nothing. */
    fun warm(context: Context) {
        if (!available()) return
        Thread({ runCatching { ensure(context.applicationContext) } }, "DexHubUsenetWarm").start()
    }

    /**
     * One release as a stream on this device. [servers] are the providers as the engine takes them
     * (nntps://user:password@host:port/connections). Throws [UsenetException].
     */
    fun open(context: Context, nzbUrl: String, servers: List<String>, season: Int?, episode: Int?, privateNetwork: Boolean): Opened {
        val (port, token) = ensure(context.applicationContext)
        val config = JSONObject()
            .put("profile", "balanced")
            .put("fastMkvStartup", true)
            .put("fastNzbFetch", true)
            .put("cacheNzb", true)
            .put("holeFilling", true)
            .put("allowPrivateNetwork", privateNetwork)
        val body = JSONObject()
            .put("nzbUrl", nzbUrl)
            .put("servers", JSONArray(servers))
            .put("keepSessionId", "")
            .put("config", config)
        if (season != null && episode != null) body.put("season", season).put("episode", episode)
        // the release played before is let go first: the engine holds one stream's connections at a time
        val previous = synchronized(this) { session.also { session = "" } }
        if (previous.isNotEmpty()) close(port, token, previous)
        val started = System.currentTimeMillis()
        var status = 0
        var answer = ""
        var failure = ""
        for (attempt in 0 until 8) {                  // 409: another release is still opening
            val request = Request.Builder().url("http://127.0.0.1:$port/sessions")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                status = response.code
                answer = response.body?.string().orEmpty()
                failure = response.header("X-Usenet-Failure").orEmpty()
            }
            if (status != 409) break
            Thread.sleep(500)
        }
        val json = runCatching { JSONObject(answer) }.getOrNull()
        if (status != 200 || json == null) throw UsenetException(answer.take(300).ifBlank { "the release did not open ($status)" }, failure)
        val id = json.optString("id")
        synchronized(this) { session = id }
        val opened = Opened(
            url = "http://127.0.0.1:$port" + json.optString("path"),
            id = id,
            filename = json.optString("filename"),
            size = json.optLong("size")
        )
        Log.i(Tag, "${opened.filename.take(70)} opened in ${(System.currentTimeMillis() - started) / 100 / 10.0} s")
        return opened
    }

    /** Whether [url] is a stream of the engine as it runs now. */
    fun serves(url: String): Boolean = port > 0 && url.startsWith("http://127.0.0.1:$port/") && healthy()

    private fun close(port: Int, token: String, id: String) {
        runCatching {
            val request = Request.Builder().url("http://127.0.0.1:$port/sessions/$id")
                .header("Authorization", "Bearer $token").delete().build()
            http.newBuilder().readTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().close()
        }
    }
}
