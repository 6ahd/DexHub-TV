package com.nuvio.tv.dexhub.livetv

/** A playlist the viewer added: an M3U address, or an Xtream panel with its sign-in. */
data class LiveSource(
    val id: String,
    val name: String,
    val kind: Kind,
    /** The M3U address, or the Xtream panel's address. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** An XMLTV guide of the viewer's own; empty takes the playlist's (or the panel's). */
    val guideUrl: String = ""
) {
    enum class Kind { M3U, XTREAM }
}

/** A live channel; [headers] are what its stream asks for (a playlist's User-Agent or Referer). */
data class LiveChannel(
    val id: String,
    val name: String,
    val number: Int?,
    val logo: String?,
    val group: String,
    val epgId: String?,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class LiveProgram(val title: String, val startMs: Long, val endMs: Long)

/** A film of a playlist or a panel. */
data class LiveMovie(
    val id: String,
    val name: String,
    val poster: String?,
    val group: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val year: String? = null,
    val rating: Double? = null
)

/** A series; a panel's episodes are asked for when it is opened ([episodes] empty until then). */
data class LiveSeries(
    val id: String,
    val name: String,
    val poster: String?,
    val group: String,
    val episodes: List<LiveEpisode> = emptyList()
)

data class LiveEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val still: String? = null,
    val headers: Map<String, String> = emptyMap()
)

/**
 * A source's channels in their groups, in the order the source gives them, where its guide is, and its films and
 * series with their own groups (v2: Dex Hub TV's Live TV shows them in tabs of their own).
 */
data class LiveLineup(
    val channels: List<LiveChannel>,
    val groups: List<String>,
    val guideUrl: String?,
    val movies: List<LiveMovie> = emptyList(),
    val movieGroups: List<String> = emptyList(),
    val series: List<LiveSeries> = emptyList(),
    val seriesGroups: List<String> = emptyList()
)

class LiveTvException(message: String) : Exception(message)
