package com.nuvio.tv.dexhub.livetv

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * An Xtream Codes panel's live channels, ported from Flix (FlixBridge/IPTV/Xtream.swift): the account is
 * checked first, then its live categories and streams are read from `player_api.php`. A stream plays from
 * `{server}/live/{user}/{pass}/{id}.ts`, or `.m3u8` when the panel offers only HLS.
 */
class XtreamPanel(server: String, private val username: String, private val password: String) {
    private val base: HttpUrl = normalise(server) ?: throw LiveTvException("The panel's address is not valid")

    fun accountUrl(): String = endpoint(null)
    fun categoriesUrl(): String = endpoint("get_live_categories")
    fun streamsUrl(): String = endpoint("get_live_streams")
    fun guideUrl(): String = base.newBuilder().addPathSegment("xmltv.php")
        .addQueryParameter("username", username).addQueryParameter("password", password).build().toString()

    /** The account's answer: throws when the panel refuses the sign-in; the live container it streams. */
    fun liveExtension(accountJson: String): String {
        val account = runCatching { JSONObject(accountJson) }.getOrNull()
            ?: throw LiveTvException("The panel did not answer as an Xtream panel")
        val user = account.optJSONObject("user_info") ?: throw LiveTvException("The panel did not accept this sign-in")
        val auth = user.opt("auth")
        if (auth?.toString() != "1") throw LiveTvException("The panel did not accept this sign-in")
        val status = user.optString("status")
        if (status.isNotEmpty() && !status.equals("active", ignoreCase = true)) {
            throw LiveTvException("This account is $status")
        }
        val formats = user.optJSONArray("allowed_output_formats")?.let { list ->
            (0 until list.length()).map { list.optString(it).lowercase() }
        }.orEmpty()
        return if ("m3u8" in formats && "ts" !in formats) "m3u8" else "ts"
    }

    fun lineup(categoriesJson: String, streamsJson: String, extension: String): LiveLineup {
        val names = LinkedHashMap<String, String>()
        runCatching { JSONArray(categoriesJson) }.getOrNull()?.let { list ->
            for (i in 0 until list.length()) {
                val category = list.optJSONObject(i) ?: continue
                val id = category.optString("category_id")
                val name = category.optString("category_name").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) names[id] = name
            }
        }
        val streams = runCatching { JSONArray(streamsJson) }.getOrNull()
            ?: throw LiveTvException("The panel did not list its channels")
        val channels = ArrayList<LiveChannel>(streams.length())
        val seen = HashSet<String>()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val id = stream.opt("stream_id")?.toString()?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(id)) continue
            val categoryId = stream.opt("category_id")?.toString().orEmpty()
            channels += LiveChannel(
                id = id,
                name = stream.optString("name").trim().ifEmpty { "Channel $id" },
                number = stream.opt("num")?.toString()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
                logo = stream.optString("stream_icon").trim().takeIf { it.startsWith("http") },
                group = names[categoryId] ?: "Other",
                epgId = stream.optString("epg_channel_id").trim().takeIf { it.isNotEmpty() && it != "null" },
                url = base.newBuilder().addPathSegment("live").addPathSegment(username).addPathSegment(password)
                    .addPathSegment("$id.$extension").build().toString()
            )
        }
        val order = names.values.toList()
        val groups = channels.map { it.group }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return LiveLineup(channels, groups, guideUrl())
    }

    private fun endpoint(action: String?): String = base.newBuilder().addPathSegment("player_api.php")
        .addQueryParameter("username", username).addQueryParameter("password", password)
        .apply { if (action != null) addQueryParameter("action", action) }
        .build().toString()

    companion object {
        /** `host:port`, `http://host:port/`, `http://host/player_api.php?...`: the panel's root. */
        fun normalise(server: String): HttpUrl? {
            var text = server.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) text = "http://$text"
            val url = text.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() && !it.endsWith(".php") }
            return url.newBuilder().query(null).fragment(null).encodedPath("/").apply {
                segments.forEach { addPathSegment(it) }
            }.build()
        }
    }
}
