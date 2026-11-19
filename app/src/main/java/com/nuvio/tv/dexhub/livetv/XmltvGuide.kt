package com.nuvio.tv.dexhub.livetv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * An XMLTV guide kept only around now, ported from Flix (FlixBridge/IPTV/XMLTVGuide.swift, itself adapted from
 * Plozz, (c) Brandon Moore, GPL-3.0-only): programmes that ended more than two hours ago or start more than a day
 * ahead are dropped while reading, so a large guide costs little memory. Gzipped guides are read as they come.
 */
class XmltvGuide private constructor(
    /** By lowercased channel id, in start order. */
    private val programs: Map<String, List<LiveProgram>>,
    /** Lowercased display name to lowercased channel id, for channels without a guide id. */
    private val channelIds: Map<String, String>
) {
    val isEmpty: Boolean get() = programs.isEmpty()

    /** The programme on now and the ones after it, by guide id or else by the channel's name. */
    fun upcoming(epgId: String?, name: String, nowMs: Long, limit: Int = 2): List<LiveProgram> {
        val byId = epgId?.lowercase()?.takeIf { programs.containsKey(it) }
        val id = byId ?: channelIds[name.lowercase()] ?: epgId?.lowercase() ?: return emptyList()
        return programs[id].orEmpty().asSequence().filter { it.endMs > nowMs }.take(limit).toList()
    }

    companion object {
        fun read(input: InputStream, nowMs: Long = System.currentTimeMillis()): XmltvGuide {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1F && buffered.read() == 0x8B
            buffered.reset()
            val stream = if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered
            val from = nowMs - 2 * 3_600_000L
            val to = nowMs + 24 * 3_600_000L
            val programs = HashMap<String, MutableList<LiveProgram>>()
            val channelIds = HashMap<String, String>()
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(stream, null)
            var channel: String? = null
            var programChannel: String? = null
            var start = 0L
            var end = 0L
            var inTitle = false
            var inDisplayName = false
            var title: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> channel = parser.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> inDisplayName = channel != null
                        "programme" -> {
                            start = parser.getAttributeValue(null, "start")?.let(::date) ?: 0L
                            end = parser.getAttributeValue(null, "stop")?.let(::date) ?: 0L
                            programChannel = parser.getAttributeValue(null, "channel")?.lowercase()
                                ?.takeIf { start in 1 until to && end > from }
                            title = null
                        }
                        "title" -> inTitle = programChannel != null && title == null
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (inTitle) title = text
                            if (inDisplayName) channel?.let { channelIds.putIfAbsent(text.lowercase(), it) }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> channel = null
                        "display-name" -> inDisplayName = false
                        "title" -> inTitle = false
                        "programme" -> {
                            val id = programChannel
                            val name = title
                            if (id != null && name != null && end > start) {
                                programs.getOrPut(id) { mutableListOf() } += LiveProgram(name, start, end)
                            }
                            programChannel = null
                        }
                    }
                }
                event = parser.next()
            }
            return XmltvGuide(programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }, channelIds)
        }

        /** `20261031203000 +0300`: `YYYYMMDDhhmm[ss]` and an optional offset (`+hhmm`, `+hh:mm`, `Z`); none is UTC. */
        internal fun date(input: String): Long? {
            val trimmed = input.trim()
            val digits = trimmed.takeWhile { it.isDigit() }
            if (digits.length != 12 && digits.length != 14) return null
            var zone = trimmed.substring(digits.length).trim().replace(":", "")
            if (zone.isEmpty() || zone in setOf("Z", "UTC", "GMT")) zone = "+0000"
            if (zone.length != 5 || (zone[0] != '+' && zone[0] != '-')) return null
            val hours = zone.substring(1, 3).toIntOrNull() ?: return null
            val minutes = zone.substring(3, 5).toIntOrNull() ?: return null
            fun n(start: Int, count: Int) = digits.substring(start, start + count).toInt()
            val year = n(0, 4); val month = n(4, 2); val day = n(6, 2)
            val hour = n(8, 2); val minute = n(10, 2); val second = if (digits.length == 14) n(12, 2) else 0
            if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
            // days since 1970 from the civil date (Howard Hinnant's algorithm)
            val y = year - if (month <= 2) 1 else 0
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
            val days = era * 146_097L + yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear - 719_468
            val offset = (if (zone[0] == '-') -1 else 1) * (hours * 3_600 + minutes * 60)
            return (days * 86_400 + hour * 3_600 + minute * 60 + second - offset) * 1000L
        }
    }
}
