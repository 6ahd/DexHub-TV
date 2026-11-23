package com.nuvio.tv.dexhub.sync

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.nuvio.tv.dexhub.ai.AiService
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The film's own speech as AutoSync's clock, heard by Whisper with the viewer's own key, ported from Dex Hub for
 * Kodi (subsync/whisperref.py, 5.10.607): when the video carries no subtitle to time by and no English subtitle of
 * the release was found, six pieces of 90 seconds spread over the film (the opening titles and the end credits left
 * out) are decoded on this device to 16 kHz mono (the centre channel of a 5.1 mix, where the dialogue sits) and sent
 * to the viewer's Groq or OpenAI account. Whisper answers with every spoken sentence and its time.
 *
 * Only sound leaves the device, about nine minutes of a two-hour film, to the viewer's own account; no link, no
 * name. A daily cap keeps it far below Groq's free day. The sentences cover pieces of the film, so AutoSync uses
 * them as a sampled reference: one delay for the whole subtitle.
 */
internal object WhisperReference {
    private const val Tag = "DexHubSync"
    private const val Rate = 16000
    private const val Pieces = 6
    private const val ShortPieces = 4
    private const val PieceS = 90
    private const val DailyCapS = 4 * 3600
    private const val MinLines = 30

    private data class Account(val service: String, val key: String, val url: String, val model: String)

    class WhisperException(val reason: String, detail: String = "") : Exception(if (detail.isEmpty()) reason else "$reason: $detail")

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()

    private fun account(context: Context): Account? {
        AiService.keyFor(context, "groq").takeIf { it.isNotBlank() }?.let {
            return Account("groq", it, "https://api.groq.com/openai/v1/audio/translations", "whisper-large-v3")
        }
        AiService.keyFor(context, "openai").takeIf { it.isNotBlank() }?.let {
            return Account("openai", it, "https://api.openai.com/v1/audio/translations", "whisper-1")
        }
        return null
    }

    fun usable(context: Context): Boolean = AiService.whisper(context) && account(context) != null

    // ---------------------------------------------------------------- the daily cap

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    private fun usedToday(context: Context): Float {
        val p = context.getSharedPreferences("dexhub_whisper", Context.MODE_PRIVATE)
        return if (p.getString("day", "") == today()) p.getFloat("seconds", 0f) else 0f
    }

    private fun spend(context: Context, seconds: Float) {
        val used = usedToday(context)
        context.getSharedPreferences("dexhub_whisper", Context.MODE_PRIVATE).edit()
            .putString("day", today()).putFloat("seconds", used + seconds).apply()
    }

    // ---------------------------------------------------------------- the sound

    /** The film's sound read like a player reads it, a piece at a time. */
    private class Sound(url: String, headers: Map<String, String>) : AutoCloseable {
        private val extractor = MediaExtractor()
        private val track: Int
        val format: MediaFormat
        private val decoderName: String
        val durationS: Double

        init {
            extractor.setDataSource(url, headers)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var picked = -1
            var name: String? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val decoder = runCatching { codecs.findDecoderForFormat(f) }.getOrNull() ?: continue
                picked = i; name = decoder; break                  // the first track a decoder here can read
            }
            if (picked < 0 || name == null) { extractor.release(); throw WhisperException("no_decoder") }
            track = picked
            decoderName = name
            format = extractor.getTrackFormat(track)
            durationS = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
            extractor.selectTrack(track)
        }

        /** [lengthS] seconds from [startS] as 16 kHz mono 16-bit samples. */
        fun piece(startS: Double, lengthS: Int, active: () -> Unit): ShortArray {
            val startUs = (startS * 1e6).toLong()
            val endUs = startUs + lengthS * 1_000_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createByCodecName(decoderName)
            val mono = FloatSink()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
                var rate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 48000)
                var floatPcm = false
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val started = System.currentTimeMillis()
                while (true) {
                    active()
                    if (System.currentTimeMillis() - started > 90_000) throw WhisperException("unread", "slow")
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time > endUs) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            rate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                            floatPcm = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        out >= 0 -> {
                            val buffer = codec.getOutputBuffer(out)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                mono.add(buffer.slice().order(ByteOrder.nativeOrder()), floatPcm, channels, rate, info.presentationTimeUs, startUs, endUs)
                            }
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                return mono.resampled(rate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        }

        override fun close() = extractor.release()
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback

    /** Mono samples at the source rate: the centre channel of a 5.1 mix, else the channels' average. */
    private class FloatSink {
        private var data = FloatArray(1 shl 16)
        private var n = 0

        fun add(buffer: ByteBuffer, floatPcm: Boolean, channels: Int, rate: Int, ptsUs: Long, startUs: Long, endUs: Long) {
            val frames = if (floatPcm) buffer.remaining() / 4 / channels else buffer.remaining() / 2 / channels
            for (k in 0 until frames) {
                val t = ptsUs + k * 1_000_000L / rate
                val base = k * channels
                fun at(c: Int): Float = if (floatPcm) buffer.getFloat((base + c) * 4) else buffer.getShort((base + c) * 2) / 32768f
                if (t < startUs || t >= endUs) continue
                val v = if (channels >= 6) at(2) else { var s = 0f; for (c in 0 until channels) s += at(c); s / channels }
                if (n == data.size) data = data.copyOf(n * 2)
                data[n++] = v
            }
        }

        /** To 16 kHz, each output the average of the source samples it covers (a gentle low-pass). */
        fun resampled(rate: Int): ShortArray {
            val step = rate.toDouble() / Rate
            val count = (n / step).toInt()
            return ShortArray(count) { i ->
                val a = (i * step).toInt()
                val b = ((i + 1) * step).toInt().coerceIn(a + 1, n)
                var s = 0f
                for (j in a until b) s += data[j]
                ((s / (b - a)).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
        }
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val bytes = pcm.size * 2
        val b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(Rate).putInt(Rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    private fun whisper(wav: ByteArray, account: Account): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", account.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "a.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(account.url).header("Authorization", "Bearer ${account.key}")
            .header("User-Agent", "DexHub").post(body).build()
        http.newCall(request).execute().use { r ->
            when (r.code) {
                200 -> return JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                401, 403 -> throw WhisperException("key", "HTTP ${r.code}")
                429 -> throw WhisperException("limit", "HTTP 429")
                else -> throw WhisperException("unread", "HTTP ${r.code}")
            }
        }
    }

    /** Every sentence Whisper heard in one piece, on the film's clock. */
    fun sentences(answer: JSONObject, offsetMs: Long): List<SubtitleSyncCue> {
        val segments = answer.optJSONArray("segments") ?: return emptyList()
        return (0 until segments.length()).mapNotNull { i ->
            val seg = segments.optJSONObject(i) ?: return@mapNotNull null
            val text = seg.optString("text").trim()
            if (text.isEmpty() || seg.optDouble("no_speech_prob", 0.0) > 0.6 || !seg.has("start")) return@mapNotNull null
            val start = offsetMs + (seg.optDouble("start") * 1000).toLong()
            val end = offsetMs + (seg.optDouble("end", seg.optDouble("start") + 2) * 1000).toLong()
            SubtitleSyncCue(start, maxOf(end, start + 300), text)
        }
    }

    /** The sentences of six pieces of the film. Throws [WhisperException] ("no_key", "cap", "key", "limit", ...). */
    suspend fun find(context: Context, playing: DexHubSyncReferences.Playing): List<SubtitleSyncCue> = withContext(Dispatchers.IO) {
        val account = account(context) ?: throw WhisperException("no_key")
        val started = System.currentTimeMillis()
        Sound(playing.streamUrl, playing.headers).use { sound ->
            val duration = sound.durationS.takeIf { it >= 60 } ?: (playing.durationMs / 1000.0)
            if (duration < 300) throw WhisperException("short")
            val count = if (duration >= 1500) Pieces else ShortPieces
            val length = minOf(PieceS, (duration * 0.1).toInt())
            if (usedToday(context) + count * length > DailyCapS) throw WhisperException("cap")
            val lo = duration * 0.05
            val hi = duration * 0.86 - length
            val starts = (0 until count).map { lo + (hi - lo) * it / (count - 1).coerceAtLeast(1) }
            val context0 = coroutineContext
            // decoding is one at a time (one reader); Whisper hears two pieces at once
            val gate = Semaphore(2)
            var heard = 0f
            val found = coroutineScope {
                starts.map { s ->
                    val pcm = sound.piece(s, length) { context0.ensureActive() }
                    heard += pcm.size / Rate.toFloat()
                    async { gate.withPermit { runCatching { sentences(whisper(wav(pcm), account), (s * 1000).toLong()) } } }
                }.awaitAll()
            }
            spend(context, heard)
            val lines = found.mapNotNull { it.getOrNull() }.flatten().sortedBy { it.startTimeMs }
            if (lines.size < MinLines) {
                val errors = found.mapNotNull { it.exceptionOrNull() }
                throw errors.firstOrNull { (it as? WhisperException)?.reason in setOf("key", "limit") }
                    ?: errors.firstOrNull() ?: WhisperException("few", "${lines.size} lines")
            }
            Log.i(Tag, "Whisper (${account.service}) heard ${lines.size} lines in $count pieces (${heard.toInt()} s of sound, ${(System.currentTimeMillis() - started) / 1000} s)")
            lines
        }
    }
}
