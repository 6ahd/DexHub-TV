package com.nuvio.tv.dexhub.usenet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The viewer's Usenet providers and indexers, kept on this device only (never synced: they hold passwords and keys).
 * The same record Dex Hub for Kodi keeps in usenet.json, so the two read alike.
 */
object UsenetConfig {
    data class Provider(
        val host: String,
        val port: Int = 563,
        val ssl: Boolean = true,
        val user: String = "",
        val password: String = "",
        val connections: Int = 20,
        /** A backup provider: read only for articles the main ones do not have. */
        val backup: Boolean = false,
        val off: Boolean = false
    )

    data class Indexer(
        val name: String,
        val url: String,
        val key: String,
        /** A Prowlarr: one search covers all of its Usenet indexers. */
        val prowlarr: Boolean = false,
        val off: Boolean = false
    )

    data class Setup(
        val providers: List<Provider> = emptyList(),
        val indexers: List<Indexer> = emptyList(),
        /** Lets the engine reach an indexer or provider on the home network. */
        val privateNetwork: Boolean = false
    ) {
        val ready: Boolean get() = providers.any { !it.off } && indexers.any { !it.off }
    }

    private const val Prefs = "dexhub_usenet"
    private const val Key = "config"
    private val state = MutableStateFlow<Setup?>(null)

    fun setup(context: Context): StateFlow<Setup> {
        if (state.value == null) state.value = read(context)
        @Suppress("UNCHECKED_CAST")
        return state.asStateFlow() as StateFlow<Setup>
    }

    fun current(context: Context): Setup = setup(context).value

    fun save(context: Context, setup: Setup) {
        state.value = setup
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putString(Key, write(setup).toString()).apply()
    }

    /** The providers as the engine takes them: nntps://user:password@host:port/connections, ?priority=1 on a backup. */
    fun servers(setup: Setup): List<String> = setup.providers.filterNot { it.off }.map { p ->
        val login = if (p.user.isNotEmpty()) "${enc(p.user)}:${enc(p.password)}@" else ""
        val port = p.port.takeIf { it > 0 } ?: if (p.ssl) 563 else 119
        "${if (p.ssl) "nntps" else "nntp"}://$login${p.host.trim()}:$port/${p.connections.coerceIn(1, 100)}" +
            if (p.backup) "?priority=1" else ""
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun read(context: Context): Setup {
        val raw = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getString(Key, null) ?: return Setup()
        return runCatching {
            val o = JSONObject(raw)
            val providers = o.optJSONArray("providers") ?: JSONArray()
            val indexers = o.optJSONArray("indexers") ?: JSONArray()
            Setup(
                providers = (0 until providers.length()).mapNotNull { i ->
                    val p = providers.optJSONObject(i) ?: return@mapNotNull null
                    Provider(
                        host = p.optString("host"),
                        port = p.optInt("port", 563),
                        ssl = p.optBoolean("ssl", true),
                        user = p.optString("user"),
                        password = p.optString("password"),
                        connections = p.optInt("connections", 20),
                        backup = p.optBoolean("backup"),
                        off = p.optBoolean("off")
                    ).takeIf { it.host.isNotBlank() }
                },
                indexers = (0 until indexers.length()).mapNotNull { i ->
                    val x = indexers.optJSONObject(i) ?: return@mapNotNull null
                    Indexer(
                        name = x.optString("name"),
                        url = x.optString("url"),
                        key = x.optString("key"),
                        prowlarr = x.optString("kind") == "prowlarr",
                        off = x.optBoolean("off")
                    ).takeIf { it.url.isNotBlank() }
                },
                privateNetwork = o.optBoolean("private")
            )
        }.getOrDefault(Setup())
    }

    private fun write(setup: Setup) = JSONObject()
        .put("providers", JSONArray(setup.providers.map { p ->
            JSONObject().put("host", p.host).put("port", p.port).put("ssl", p.ssl).put("user", p.user)
                .put("password", p.password).put("connections", p.connections).put("backup", p.backup).put("off", p.off)
        }))
        .put("indexers", JSONArray(setup.indexers.map { x ->
            JSONObject().put("name", x.name).put("url", x.url).put("key", x.key)
                .put("kind", if (x.prowlarr) "prowlarr" else "newznab").put("off", x.off)
        }))
        .put("private", setup.privateNetwork)
}
