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

/** A source's channels in their groups, in the order the source gives them, and where its guide is. */
data class LiveLineup(
    val channels: List<LiveChannel>,
    val groups: List<String>,
    val guideUrl: String?
)

class LiveTvException(message: String) : Exception(message)
