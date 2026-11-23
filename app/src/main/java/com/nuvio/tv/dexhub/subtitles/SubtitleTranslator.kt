package com.nuvio.tv.dexhub.subtitles

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * A subtitle translated line by line, its timing kept, ported from Dex Hub for Kodi (resources/lib/subsync/
 * subtrans.py, 5.10.588+): SRT, WebVTT and ASS are read; each line goes out as one line (its breaks joined, its
 * tags dropped) and is laid out again for the screen; Google Translate's free address takes a few thousand
 * characters a request, two at a time, and its second address (the browser extension's) after a 429.
 */
object SubtitleTranslator {
    data class Line(val startMs: Long, val endMs: Long, val text: String)

    class TranslateException(val reason: String, detail: String = "") : Exception(if (detail.isEmpty()) reason else "$reason: $detail")

    private const val GoogleUrl = "https://translate.googleapis.com/translate_a/single"
    private const val GoogleAltUrl = "https://clients5.google.com/translate_a/t"
    private const val GoogleChars = 4200
    private const val Workers = 2
    private const val LineWidth = 42
    private const val AltForMs = 600_000L

    private val tag = Regex("<[^>]{1,40}>")
    private val assTag = Regex("\\{[^}]*}")
    private val position = Regex("""^\s*\{\\an?\d}""")
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val arrow = Regex("""^\s*([0-9:.,]+)\s*-->\s*([0-9:.,]+)""")
    @Volatile private var altUntil = 0L

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    // ---------------------------------------------------------------- reading a subtitle

    private fun ms(stamp: String): Long? {
        val m = time.find(stamp) ?: return null
        val (h, mi, s, frac) = m.destructured
        return (((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000) + (frac + "00").take(3).toLong()
    }

    private fun assMs(stamp: String): Long? = runCatching {
        val (h, m, rest) = stamp.trim().split(':')
        val sec = rest.substringBefore('.')
        val cs = (rest.substringAfter('.', "0") + "00").take(2)
        ((h.toLong() * 60 + m.toLong()) * 60 + sec.toLong()) * 1000 + cs.toLong() * 10
    }.getOrNull()

    /** The lines of an SRT, WebVTT or ASS/SSA subtitle, by start. */
    fun parse(raw: String): List<Line> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿')
        if ("[Events]" in text || text.trimStart().startsWith("[Script Info]")) return parseAss(text)
        val out = ArrayList<Line>()
        for (block in text.split(Regex("""\n\s*\n"""))) {
            val rows = block.split('\n')
            for ((i, row) in rows.withIndex()) {
                val m = arrow.find(row) ?: continue
                val a = ms(m.groupValues[1])
                val b = ms(m.groupValues[2])
                val body = rows.drop(i + 1).joinToString("\n").trim()
                if (a != null && b != null && body.isNotEmpty()) out += Line(a, b, body)
                break
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseAss(text: String): List<Line> {
        val out = ArrayList<Line>()
        var fields: List<String>? = null
        var inEvents = false
        for (row in text.split('\n')) {
            val s = row.trim()
            if (s.startsWith("[")) { inEvents = s.equals("[events]", true); continue }
            if (!inEvents) continue
            if (s.startsWith("format:", true)) { fields = s.substring(7).split(',').map { it.trim().lowercase() }; continue }
            val f = fields ?: continue
            if (!s.startsWith("dialogue:", true)) continue
            val parts = s.substring(9).split(',', limit = f.size)
            if (parts.size != f.size) continue
            val event = f.zip(parts).toMap()
            val a = event["start"]?.let(::assMs)
            val b = event["end"]?.let(::assMs)
            val body = event["text"].orEmpty().replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
            if (a != null && b != null && body.isNotEmpty()) out += Line(a, b, body)
        }
        return out.sortedBy { it.startMs }
    }

    // ---------------------------------------------------------------- a line out and back

    /** The line as one line to translate, and whether it was a dialogue ("- a" / "- b"). */
    fun flatten(text: String): Pair<String, Boolean> {
        val body = assTag.replace(tag.replace(position.replace(text, ""), ""), "")
        val rows = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val dialogue = rows.size > 1 && rows.all { it.startsWith("-") || it.startsWith("‐") || it.startsWith("–") }
        return rows.joinToString(" ") to dialogue
    }

    /** The translated line for the screen: two speakers on two lines, a long line cut in two near its middle. */
    fun layout(raw: String, dialogue: Boolean, width: Int = LineWidth): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (dialogue) {
            val parts = text.split(Regex("""\s+[-‐–]\s*(?=\S)""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) return parts.joinToString("\n") { if (it.startsWith("-") || it.startsWith("‐") || it.startsWith("–")) it else "- $it" }
        }
        if (text.length <= width || ' ' !in text) return text
        val middle = text.length / 2
        val cut = text.indices.filter { text[it] == ' ' }.minByOrNull { kotlin.math.abs(it - middle) } ?: return text
        return text.substring(0, cut) + "\n" + text.substring(cut + 1)
    }

    fun renderSrt(lines: List<Line>): String = buildString {
        lines.forEachIndexed { n, line ->
            append(n + 1).append('\n').append(srtTime(line.startMs)).append(" --> ").append(srtTime(line.endMs)).append('\n')
            append(line.text).append("\n\n")
        }
    }

    private fun srtTime(value: Long): String {
        val t = value.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    // ---------------------------------------------------------------- Google

    private fun post(url: String, form: FormBody): Pair<Int, String> {
        val request = Request.Builder().url(url).post(form).header("User-Agent", "Mozilla/5.0").build()
        return http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun googleAlt(texts: List<String>, target: String): List<String>? {
        val url = GoogleAltUrl.toHttpUrl().newBuilder().addQueryParameter("client", "dict-chrome-ex")
            .addQueryParameter("sl", "auto").addQueryParameter("tl", target).build().toString()
        val form = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val (status, body) = post(url, form)
        if (status == 429) throw TranslateException("busy", "google 429 (both addresses)")
        if (status != 200) throw TranslateException("service", "google alt $status")
        val list = runCatching { JSONArray(body) }.getOrNull() ?: throw TranslateException("service", "google alt answer")
        val got = (0 until list.length()).map { i ->
            val item = list.get(i)
            (if (item is JSONArray) item.optString(0) else item.toString()).trim()
        }
        return got.takeIf { it.size == texts.size }
    }

    private fun googleBatch(texts: List<String>, target: String): List<String>? {
        if (System.currentTimeMillis() < altUntil) return googleAlt(texts, target)
        val url = GoogleUrl.toHttpUrl().newBuilder().addQueryParameter("client", "gtx").addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target).addQueryParameter("dt", "t").build().toString()
        val (status, body) = post(url, FormBody.Builder().add("q", texts.joinToString("\n")).build())
        if (status == 429) {
            altUntil = System.currentTimeMillis() + AltForMs
            return googleAlt(texts, target)
        }
        if (status != 200) throw TranslateException("service", "google $status")
        val joined = runCatching {
            val segments = JSONArray(body).getJSONArray(0)
            buildString {
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONArray(i) ?: continue
                    if (!seg.isNull(0)) append(seg.optString(0))
                }
            }
        }.getOrNull() ?: throw TranslateException("service", "google answer")
        return joined.split('\n').map { it.trim() }.takeIf { it.size == texts.size }
    }

    /** One batch, cut in two (and again) when the answer's lines do not match the question's. */
    private fun batch(texts: List<String>, target: String): List<String> {
        googleBatch(texts, target)?.let { return it }
        if (texts.size == 1) return texts
        val half = texts.size / 2
        return batch(texts.subList(0, half), target) + batch(texts.subList(half, texts.size), target)
    }

    /**
     * [lines] in [target] (a language code such as "ar"), their timing kept. Lines from [first] (where the viewer
     * is) go out first; [progress] hears how much is done, 0 to 1.
     */
    suspend fun translate(lines: List<Line>, target: String, first: Int = 0, progress: (Float) -> Unit = {}): List<Line> {
        val flat = lines.map { flatten(it.text) }
        val order = (first.coerceIn(0, lines.size) until lines.size) + (0 until first.coerceIn(0, lines.size))
        val todo = order.filter { flat[it].first.isNotBlank() }
        val chunks = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var size = 0
        for (i in todo) {
            val n = flat[i].first.length + 1
            if (current.isNotEmpty() && size + n > GoogleChars) { chunks += current; current = ArrayList(); size = 0 }
            current += i
            size += n
        }
        if (current.isNotEmpty()) chunks += current
        val out = arrayOfNulls<String>(lines.size)
        val gate = Semaphore(Workers)
        var done = 0
        coroutineScope {
            chunks.map { chunk ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        val translated = batch(chunk.map { flat[it].first }, target)
                        chunk.forEachIndexed { k, i -> out[i] = layout(translated[k], flat[i].second) }
                        synchronized(out) { done += chunk.size }
                        progress(done.toFloat() / todo.size.coerceAtLeast(1))
                    }
                }
            }.awaitAll()
        }
        return lines.mapIndexed { i, line -> line.copy(text = out[i] ?: line.text) }
    }
}
