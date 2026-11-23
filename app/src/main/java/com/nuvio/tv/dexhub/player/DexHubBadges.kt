package com.nuvio.tv.dexhub.player

import com.nuvio.tv.R
import com.nuvio.tv.core.streams.StreamBadgeRulesParser
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The player's badges as Dex Hub offers them (DEXHUB.md, after Dex Hub for Kodi's player_badges.py): the badge
 * packs Dex Hub knows, one press each. A pack is read once and kept with Arc's own badge rules (the same as one
 * imported from the phone page), and the player shows it; the sources page keeps the pack it had.
 */
internal object DexHubBadges {
    enum class Style(val label: Int, val url: String?) {
        BUILT_IN(R.string.dexhub_badges_builtin, null),
        ELITE(R.string.dexhub_badges_elite, "https://raw.githubusercontent.com/leonevz/Elite-Badges/main/badges.json"),
        GOLD(R.string.dexhub_badges_gold, "https://raw.githubusercontent.com/k45sle/NUVIO_BADGES/refs/heads/main/GOLD-NONAMES.json"),
        WHITE(R.string.dexhub_badges_white, "https://raw.githubusercontent.com/sweatycab/nuvio-minimalist-badges/main/badges-white.json"),
        OFF(R.string.dexhub_badges_off, null),
    }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun current(settings: StreamBadgeSettings?): Style {
        if (settings == null) return Style.BUILT_IN
        if (!settings.showInPlayer) return Style.OFF
        return Style.entries.firstOrNull { it.url != null && it.url.equals(settings.playerBadgeSource, ignoreCase = true) }
            ?: Style.BUILT_IN
    }

    /** Puts [style] on the player. False when its pack could not be read. */
    suspend fun apply(store: StreamBadgeSettingsDataStore, style: Style): Boolean {
        when (style) {
            Style.OFF -> { store.setShowInPlayer(false); return true }
            Style.BUILT_IN -> { store.setShowInPlayer(true); store.setPlayerBadgeSource(""); return true }
            else -> {
                val url = style.url ?: return false
                val payload = withContext(Dispatchers.IO) {
                    runCatching {
                        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                    }.getOrNull()
                } ?: return false
                val import = runCatching { StreamBadgeRulesParser.parse(sourceUrl = url, payload = payload) }.getOrNull() ?: return false
                if (import.filters.isEmpty()) return false
                val settings = store.settings.first()
                val keepActive = settings.rules.normalized().imports.isNotEmpty()
                // the sources page keeps its active pack; the player is pointed at this one
                store.setStreamBadgeRules(settings.rules.upsert(import, activate = !keepActive))
                store.setPlayerBadgeSource(url)
                store.setShowInPlayer(true)
                return true
            }
        }
    }
}
