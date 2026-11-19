package com.nuvio.tv.dexhub.livetv

import java.net.URI
import java.net.URLDecoder

/**
 * An M3U playlist's live channels, ported from Flix (Flix/Packages/FlixBridge/Sources/FlixBridge/IPTV/
 * M3UPlaylist.swift, itself adapted from Plozz, (c) Brandon Moore, GPL-3.0-only).
 *
 * Entries an explicit `tvg-type` marks as VOD, `/movie/` and `/series/` addresses, `S01E02` names and
 * entries with a positive duration are films or episodes, not channels: films go to [LiveLineup.movies], and
 * episodes to [LiveLineup.series] under their show's name.
 */
object M3uPlaylist {
    private val episodePattern = Regex("""(?i)^(.+?)[ ._-]+S(\d{1,3})[ ._-]*E(\d{1,4})(?:\b|[ ._-])""")

    fun parse(text: String, baseUrl: String? = null): LiveLineup {
        val guideUrls = mutableListOf<String>()
        val channels = mutableListOf<LiveChannel>()
        val groups = LinkedHashSet<String>()
        val movies = mutableListOf<LiveMovie>()
        val movieGroups = LinkedHashSet<String>()
        val shows = LinkedHashMap<String, LiveSeries>()
        val episodes = HashMap<String, MutableList<LiveEpisode>>()
        val seriesGroups = LinkedHashSet<String>()
        val seen = HashSet<String>()
        var pending: Pending? = null
        var awaitingUrl = false
        var carried = mutableMapOf<String, String>()
        var defaultGroup: String? = null
        var sawHeader = false
        var position = 0
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (!sawHeader && channels.isEmpty() && line.startsWith("﻿")) line = line.substring(1).trim()
            if (line.isEmpty()) continue
            when (directive(line)) {
                "#EXTM3U" -> {
                    sawHeader = true
                    val attributes = attributes(line.drop(7))
                    for (key in listOf("url-tvg", "x-tvg-url", "tvg-url")) {
                        for (address in attributes[key].orEmpty().split(",")) {
                            httpUrl(address.trim(), baseUrl)?.let { if (it !in guideUrls) guideUrls += it }
                        }
                    }
                }
                "#EXT-X-STREAM-INF:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:" ->
                    throw LiveTvException("This address is a single stream, not a playlist")
                "#EXTINF:" -> {
                    pending = extinf(line)?.let { it.copy(headers = (carried + it.headers).toMutableMap()) }
                    carried = mutableMapOf()
                    awaitingUrl = true
                }
                "#EXTGRP:" -> defaultGroup = clean(line.drop(8))
                "#EXTVLCOPT:" -> {
                    val header = vlcOption(line) ?: continue
                    val current = pending
                    if (current != null) current.headers[header.first] = header.second
                    else if (!awaitingUrl) carried[header.first] = header.second
                }
                else -> {
                    if (line.startsWith("#")) continue
                    if (!sawHeader && channels.isEmpty() && pending == null && line.startsWith("<")) {
                        throw LiveTvException("This address is not an M3U playlist")
                    }
                    if (!awaitingUrl) {
                        // a bare address is an entry only when absolute; stray text is not a relative address
                        if (httpUrl(line.substringBefore('|'), null) == null) continue
                        pending = Pending("", emptyMap(), null, carried.toMutableMap())
                    }
                    awaitingUrl = false
                    carried = mutableMapOf()
                    val entry = pending ?: continue
                    pending = null
                    val url = httpUrl(line.substringBefore('|'), baseUrl) ?: continue
                    if ('|' in line) entry.headers.putAll(pipeHeaders(line.substringAfter('|')))
                    position++
                    when (val found = classify(entry, url, defaultGroup, position)) {
                        is Found.Channel -> if (seen.add(found.channel.id)) {
                            groups += found.channel.group
                            channels += found.channel
                        }
                        is Found.Movie -> if (seen.add(found.movie.id)) {
                            movieGroups += found.movie.group
                            movies += found.movie
                        }
                        is Found.Episode -> if (seen.add(found.episode.id)) {
                            val show = shows.getOrPut(found.showId) {
                                seriesGroups += found.group
                                LiveSeries(found.showId, found.show, found.episode.still, found.group)
                            }
                            episodes.getOrPut(show.id) { mutableListOf() } += found.episode
                        }
                    }
                }
            }
        }
        if (!sawHeader && channels.isEmpty() && movies.isEmpty() && shows.isEmpty()) {
            throw LiveTvException("This address is not an M3U playlist")
        }
        val series = shows.values.map { show ->
            show.copy(episodes = episodes[show.id].orEmpty().sortedWith(compareBy({ it.season }, { it.episode })))
        }
        return LiveLineup(
            channels, groups.toList(), guideUrls.firstOrNull(),
            movies, movieGroups.toList(), series, seriesGroups.toList()
        )
    }

    private sealed class Found {
        class Channel(val channel: LiveChannel) : Found()
        class Movie(val movie: LiveMovie) : Found()
        class Episode(val showId: String, val show: String, val group: String, val episode: LiveEpisode) : Found()
    }

    private class Pending(
        val name: String,
        val attributes: Map<String, String>,
        val duration: Double?,
        val headers: MutableMap<String, String>
    ) {
        fun copy(headers: MutableMap<String, String>) = Pending(name, attributes, duration, headers)
    }

    private fun hash(key: String) = (key.hashCode().toLong() and 0xFFFFFFFFL).toString(36)

    /** A channel, a film or an episode, as Flix sorts a playlist's entries. */
    private fun classify(entry: Pending, url: String, defaultGroup: String?, position: Int): Found {
        val attributes = entry.attributes
        val type = (attributes["tvg-type"] ?: attributes["type"] ?: "").lowercase()
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase()
        val name = entry.name.ifEmpty { "Channel $position" }
        val group = clean(attributes["group-title"])?.split(";")?.firstOrNull()?.let(::clean)
            ?: defaultGroup ?: "Other"
        val tvgId = clean(attributes["tvg-id"])
        val logo = (clean(attributes["tvg-logo"]) ?: clean(attributes["logo"]))?.let { httpUrl(it, null) }
        val key = listOf(tvgId.orEmpty(), name, group, url.substringAfterLast('/')).joinToString("\u001F")
        if (type != "live") {
            val numbering = episodeNumbering(name, attributes)
            val isSeries = type == "series" || type == "episode" || "/series/" in path || numbering != null
            val isMovie = type == "movie" || type == "vod" || "/movie/" in path || (entry.duration ?: 0.0) > 0.0
            if (isSeries && numbering != null) {
                val (show, season, episode) = numbering
                return Found.Episode(
                    hash(show + "\u001F" + group), show, group,
                    LiveEpisode(hash(key), season, episode, name, url, logo, entry.headers.toMap())
                )
            }
            if (isSeries || isMovie) {
                return Found.Movie(LiveMovie(hash(key), name, logo, group, url, entry.headers.toMap()))
            }
        }
        return Found.Channel(LiveChannel(
            id = hash(key),
            name = name,
            number = attributes["tvg-chno"]?.toIntOrNull()?.takeIf { it > 0 },
            logo = logo,
            group = group,
            epgId = tvgId,
            url = url,
            headers = entry.headers.toMap()
        ))
    }

    /** Show, season and episode from `series-name`/`season-number`/`episode-number`, or a name such as `Show S01E02`. */
    private fun episodeNumbering(name: String, attributes: Map<String, String>): Triple<String, Int, Int>? {
        val show = attributes["series-name"]
        val season = attributes["season-number"]?.toIntOrNull()
        val episode = attributes["episode-number"]?.toIntOrNull()
        if (show != null && season != null && episode != null) return Triple(show, season, episode)
        val match = episodePattern.find(name) ?: return null
        return Triple(match.groupValues[1].trim(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    /** `#EXTINF`, `#EXTM3U`, ... uppercased with its colon, or "" for a line that is not one. */
    internal fun directive(line: String): String {
        if (!line.startsWith("#")) return ""
        val end = line.indexOfFirst { it == ' ' || it == '\t' || it == ':' }
        return when {
            end < 0 -> line.uppercase()
            line[end] == ':' -> line.substring(0, end + 1).uppercase()
            else -> line.substring(0, end).uppercase()
        }
    }

    /** `#EXTINF:-1 tvg-id="..." group-title="...",Name`; the name follows the first comma outside quotes. */
    private fun extinf(line: String): Pending? {
        val payload = line.substringAfter(':', "")
        var quote: Char? = null
        var comma = -1
        for ((index, c) in payload.withIndex()) {
            if (c == '"' || c == '\'') {
                quote = if (quote == c) null else quote ?: c
            } else if (c == ',' && quote == null) {
                comma = index
                break
            }
        }
        if (comma < 0) return null
        val metadata = payload.substring(0, comma)
        val attributes = attributes(metadata)
        val name = clean(payload.substring(comma + 1)) ?: clean(attributes["tvg-name"]) ?: ""
        val headers = mutableMapOf<String, String>()
        for ((key, header) in listOf(
            "http-user-agent" to "User-Agent", "user-agent" to "User-Agent", "http-referrer" to "Referer",
            "http-referer" to "Referer", "referer" to "Referer", "referrer" to "Referer"
        )) {
            attributes[key]?.let(::headerValue)?.let { headers[header] = it }
        }
        val duration = metadata.takeWhile { it != ' ' && it != '\t' }.toDoubleOrNull()?.takeIf { it > 0 }
        return Pending(name, attributes, duration, headers)
    }

    /** `key="value" key2=value2`, keys lowercased; a bare token (the duration) is skipped. */
    internal fun attributes(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var i = 0
        fun space(c: Char) = c == ' ' || c == '\t'
        while (i < text.length) {
            while (i < text.length && space(text[i])) i++
            val keyStart = i
            while (i < text.length && text[i] != '=' && !space(text[i])) i++
            if (keyStart == i) break
            val keyEnd = i
            while (i < text.length && space(text[i])) i++
            if (i >= text.length || text[i] != '=') { i = keyEnd; continue }
            i++
            while (i < text.length && space(text[i])) i++
            if (i >= text.length) break
            val value: String
            if (text[i] == '"' || text[i] == '\'') {
                val q = text[i]
                val start = ++i
                while (i < text.length && text[i] != q) i++
                value = text.substring(start, i)
                if (i < text.length) i++
            } else {
                val start = i
                while (i < text.length && !space(text[i])) i++
                value = text.substring(start, i)
            }
            result[text.substring(keyStart, keyEnd).lowercase()] = value
        }
        return result
    }

    /** `#EXTVLCOPT:http-user-agent=...` and its kin. */
    private fun vlcOption(line: String): Pair<String, String>? {
        val equals = line.indexOf('=')
        if (equals < 11) return null
        val key = line.substring(11, equals).trim().lowercase()
        var value = line.substring(equals + 1).trim()
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
            value = value.substring(1, value.length - 1)
        }
        val name = when (key) {
            "http-user-agent" -> "User-Agent"
            "http-referrer", "http-referer" -> "Referer"
            "http-origin" -> "Origin"
            "http-cookie" -> "Cookie"
            else -> return null
        }
        return headerValue(value)?.let { name to it }
    }

    /** `url|User-Agent=...&Referer=...`, values percent-decoded. */
    private fun pipeHeaders(suffix: String): Map<String, String> = suffix.split('&').mapNotNull { pair ->
        val equals = pair.indexOf('=')
        if (equals <= 0) return@mapNotNull null
        val key = pair.substring(0, equals).trim()
        val raw = pair.substring(equals + 1)
        val value = headerValue(runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)) ?: return@mapNotNull null
        val name = when (key.lowercase()) {
            "user-agent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            else -> key
        }
        name to value
    }.toMap()

    private fun headerValue(text: String): String? {
        val value = text.trim()
        if (value.isEmpty() || value.length > 4096 || value.any { it.isISOControl() }) return null
        return value
    }

    /** An absolute http(s) address; availability notes such as `[NO STREAM]` are not addresses. */
    internal fun httpUrl(text: String, base: String?): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed.startsWith("[") && trimmed.endsWith("]"))) return null
        return runCatching {
            val uri = if (base != null) URI(base).resolve(trimmed.replace(" ", "%20")) else URI(trimmed.replace(" ", "%20"))
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) uri.toString() else null
        }.getOrNull()
    }

    internal fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
