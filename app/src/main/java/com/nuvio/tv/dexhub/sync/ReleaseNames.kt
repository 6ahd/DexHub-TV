package com.nuvio.tv.dexhub.sync

import java.net.URLDecoder
import java.util.Locale

/**
 * How close a subtitle's release name is to the playing file's, ported from Dex Hub for Kodi (subsync/dexpick.py,
 * from DexSubtitles): resolution, source family, streaming service, codec, edition, release group and the other
 * words. About 0 to 100; 60 and up means the same group and source, so the same release.
 */
object ReleaseNames {
    data class Info(
        val res: String = "",
        val src: String = "",
        val service: String = "",
        val codec: String = "",
        val edition: Set<String> = emptySet(),
        val group: String = "",
        val words: Set<String> = emptySet(),
    ) {
        val hasHints: Boolean get() = res.isNotEmpty() || src.isNotEmpty() || service.isNotEmpty() || group.isNotEmpty()
        val weight: Int get() = listOf(res, src, service, codec, group).count { it.isNotEmpty() } * 3 + words.size
    }

    private val resolutions = mapOf("2160p" to "2160", "4k" to "2160", "uhd" to "2160", "1080p" to "1080", "1080i" to "1080",
        "720p" to "720", "576p" to "576", "480p" to "480", "360p" to "360")
    private val sources = mapOf("bluray" to "bluray", "bdrip" to "bluray", "brrip" to "bluray", "bdremux" to "bluray", "remux" to "bluray",
        "bd" to "bluray", "bdr" to "bluray", "bd25" to "bluray", "bd50" to "bluray",
        "webdl" to "webdl", "web" to "webdl", "webrip" to "webrip", "webcap" to "webrip",
        "hdtv" to "hdtv", "pdtv" to "hdtv", "sdtv" to "hdtv", "dsr" to "hdtv", "tvrip" to "hdtv",
        "dvdrip" to "dvd", "dvd" to "dvd", "dvdr" to "dvd", "dvd5" to "dvd", "dvd9" to "dvd", "dvdscr" to "dvd",
        "hdrip" to "hdrip", "hdcam" to "cam", "cam" to "cam", "camrip" to "cam", "telesync" to "cam", "hdts" to "cam")
    private val services = mapOf("amzn" to "amzn", "amazon" to "amzn", "nf" to "nf", "netflix" to "nf", "dsnp" to "dsnp", "dsny" to "dsnp",
        "hmax" to "max", "max" to "max", "atvp" to "atvp", "hulu" to "hulu", "pcok" to "pcok", "pmtp" to "pmtp",
        "stan" to "stan", "crav" to "crav", "shahid" to "shahid", "osn" to "osn", "osnplus" to "osn",
        "itunes" to "itunes", "it" to "itunes", "roku" to "roku", "ip" to "iplayer", "iplayer" to "iplayer")
    private val codecs = mapOf("x264" to "264", "h264" to "264", "avc" to "264", "x265" to "265", "h265" to "265", "hevc" to "265", "av1" to "av1")
    private val editions = mapOf("extended" to "extended", "unrated" to "unrated", "uncut" to "unrated", "directors" to "directors",
        "dc" to "directors", "imax" to "imax", "theatrical" to "theatrical", "remastered" to "remastered",
        "criterion" to "criterion", "final" to "final")
    private val noise = setOf("ar", "ara", "arabic", "en", "eng", "english", "srt", "ass", "ssa", "vtt", "sub", "subs",
        "mkv", "mp4", "avi", "m2ts", "ts", "proper", "repack", "real", "internal", "dl", "hdr", "hdr10",
        "dv", "sdr", "aac", "ac3", "eac3", "ddp", "dd", "dts", "truehd", "atmos", "10bit", "8bit",
        "h", "x", "the", "and", "of", "a", "dexsubtitles", "external", "forced", "default", "sdh", "hi")

    private fun clean(text: String): String {
        var s = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text).lowercase(Locale.ROOT)
        s = s.substringBefore('|').substringBefore('?').replace('\\', '/').substringAfterLast('/')
        s = s.replace(Regex("""\.(srt|ass|ssa|vtt|sub|zip|mkv|mp4|avi|m2ts|ts|webm|mov)$"""), "")
        s = s.replace("web-dl", "webdl").replace("web dl", "webdl").replace("blu-ray", "bluray")
            .replace("dd+", "ddp").replace("h.264", "h264").replace("h.265", "h265")
        return s.replace(Regex("""\bdirector'?s\b"""), "directors")
    }

    private fun group(clean: String): String {
        var s = clean.replace(Regex("""([ ._-](ar|ara|arabic|en|eng|forced|sdh|hi))+$"""), "")
        s = s.replace(Regex("""\s*[\[(][^\])]*[\])]\s*$"""), "").trim()
        val g = Regex("""-([a-z0-9]{2,20})$""").find(s)?.groupValues?.get(1) ?: return ""
        return if (g in resolutions || g in sources || g in codecs || g in noise || g.all(Char::isDigit)) "" else g
    }

    fun info(text: String): Info {
        val c = clean(text)
        val tokens = c.split(Regex("""[\s._\[\]()\-+,{}]+""")).filter { it.isNotEmpty() }
        var res = ""; var src = ""; var service = ""; var codec = ""
        val edition = HashSet<String>()
        val words = HashSet<String>()
        val grp = group(c)
        for (t in tokens) {
            when {
                t in resolutions && res.isEmpty() -> res = resolutions.getValue(t)
                t in sources -> {
                    val family = sources.getValue(t)
                    if (src.isEmpty() || (src == "webdl" && family == "webrip")) src = family
                }
                t in services && service.isEmpty() -> service = services.getValue(t)
                t in codecs && codec.isEmpty() -> codec = codecs.getValue(t)
                t in editions -> edition += editions.getValue(t)
                t !in noise && !t.matches(Regex("""^(19|20)\d\d$""")) && !t.matches(Regex("""^s\d+e\d+$""")) -> words += t
            }
        }
        if (grp.isNotEmpty()) words -= grp
        return Info(res, src, service, codec, edition, grp, words)
    }

    fun score(video: Info, sub: Info): Double {
        var score = 0.0
        if (video.group.isNotEmpty() && sub.group.isNotEmpty() && video.group == sub.group) score += 40
        if (video.src.isNotEmpty() && sub.src.isNotEmpty()) {
            score += when {
                video.src == sub.src -> 25.0
                setOf(video.src, sub.src) == setOf("webdl", "webrip") -> 12.0
                else -> -10.0
            }
        }
        if (video.service.isNotEmpty() && sub.service.isNotEmpty()) score += if (video.service == sub.service) 12 else -4
        if (video.res.isNotEmpty() && video.res == sub.res) score += 8
        if (video.codec.isNotEmpty() && video.codec == sub.codec) score += 3
        if (video.edition.isNotEmpty() || sub.edition.isNotEmpty()) score += if (video.edition.intersect(sub.edition).isNotEmpty()) 10 else -5
        if (video.words.isNotEmpty() && sub.words.isNotEmpty()) {
            val union = (video.words + sub.words).size
            if (union > 0) score += 15.0 * video.words.intersect(sub.words).size / union
        }
        return score.coerceAtLeast(0.0)
    }

    /** Of several texts (the file name, the link, the stream's title) the one that tells most about the release. */
    fun best(texts: List<String?>): Info = texts.filterNotNull().filter { it.isNotBlank() }.map(::info).maxByOrNull { it.weight } ?: Info()

    private val cuts = listOf(
        "extended" to Regex("extended"), "directors" to Regex("""director'?s?[ ._-]*cut|\bdc\b"""),
        "unrated" to Regex("unrated|uncut"), "final" to Regex("""final[ ._-]*cut"""),
        "special" to Regex("""special[ ._-]*edition"""), "ultimate" to Regex("""ultimate[ ._-]*(cut|edition)"""),
        "alternate" to Regex("""alternate[ ._-]*cut"""),
    )

    /** The film's cut by its name (none for the theatrical cut): another cut is never this release's clock. */
    fun cut(name: String): Set<String> {
        val low = name.lowercase(Locale.ROOT)
        return cuts.filter { (_, rx) -> rx.containsMatchIn(low) }.map { it.first }.toSet()
    }
}
