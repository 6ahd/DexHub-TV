package com.nuvio.tv.dexhub.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import com.nuvio.tv.ui.screens.player.autosync.ReferenceTrack

/**
 * Dex Hub's own clocks for AutoSync (DEXHUB.md), from Dex Hub for Kodi: when the video carries no subtitle to time
 * by, Arc's AutoSync gave up; now it asks here first, in Dex Hub's order:
 *  1. DexWorld's shared sync ([DexWorldSync]): a fix it trusts for this Arabic subtitle, or the release's timeline;
 *  2. an English subtitle of this very release ([EnglishReference]), a whole-film reference;
 *  3. the film's speech heard by Whisper with the viewer's key ([WhisperReference]), a sampled reference.
 * When the video has a subtitle of its own, its start times are shared with DexWorld ([embedded]).
 * Arc's own matcher then times the subtitle by it, as by an embedded track.
 */
internal object DexHubSyncReferences {
    private const val Tag = "DexHubSync"

    data class Playing(
        val streamUrl: String,
        val headers: Map<String, String>,
        val imdb: String,
        val season: Int?,
        val episode: Int?,
        /** The file name, the stream's name and description: what tells the release. */
        val names: List<String?>,
        val durationMs: Long,
    )

    private lateinit var app: Context
    @Volatile private var playing: Playing? = null
    /** Whisper's sentences (or its failure, as null) for the last stream: a second pass does not send the sound again. */
    @Volatile private var heard: Pair<String, List<SubtitleSyncCue>?>? = null
    private val main = Handler(Looper.getMainLooper())

    /** From the player when AutoSync starts: what is playing. */
    fun playing(
        context: Context,
        streamUrl: String,
        headers: Map<String, String>,
        id: String?,
        season: Int?,
        episode: Int?,
        names: List<String?>,
        durationMs: Long,
    ) {
        app = context.applicationContext
        val imdb = id.orEmpty().substringBefore(':').takeIf { it.startsWith("tt") }.orEmpty()
        val parts = id.orEmpty().split(':')
        playing = Playing(
            streamUrl = streamUrl,
            headers = headers,
            imdb = imdb,
            season = season ?: parts.getOrNull(1)?.toIntOrNull(),
            episode = episode ?: parts.getOrNull(2)?.toIntOrNull(),
            names = names + streamUrl,
            durationMs = durationMs,
        )
    }

    /** References for [sourceKey] when the video has none of its own; empty when none was found. */
    suspend fun find(sourceKey: String, selectedRaw: String?, selectedCues: List<SubtitleSyncCue>): List<ReferenceTrack> {
        val now = playing?.takeIf { it.streamUrl == sourceKey } ?: return emptyList()
        runCatching { DexWorldSync.reference(app, now, selectedRaw, selectedCues) }.onFailure { Log.w(Tag, "sync share failed", it) }
            .getOrNull()?.let { (cues, kind) ->
                say(app.getString(R.string.dexhub_sync_shared))
                return listOf(track("dexhub-shared-$kind", null, cues, "DexWorld", sampled = false))
            }
        runCatching { EnglishReference.find(now) }.onFailure { Log.w(Tag, "English reference failed", it) }.getOrNull()?.let { found ->
            say(app.getString(R.string.dexhub_sync_english))
            return listOf(track("dexhub-english", "en", found.cues, "English · ${found.name}", sampled = false))
        }
        heard?.let { (url, cues) ->
            if (url == sourceKey) return cues?.let { listOf(track("dexhub-whisper", null, it, "Whisper", sampled = true)) }.orEmpty()
        }
        if (WhisperReference.usable(app)) {
            say(app.getString(R.string.dexhub_sync_whisper))
            try {
                val cues = WhisperReference.find(app, now)
                heard = sourceKey to cues
                return listOf(track("dexhub-whisper", null, cues, "Whisper", sampled = true))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? WhisperReference.WhisperException)?.reason
                heard = sourceKey to null
                Log.w(Tag, "Whisper reference failed: ${e.message}")
                when (reason) {
                    "key" -> say(app.getString(R.string.dexhub_sync_whisper_key))
                    "limit", "cap" -> say(app.getString(R.string.dexhub_sync_whisper_limit))
                    "no_decoder" -> say(app.getString(R.string.dexhub_sync_whisper_no_sound))
                }
            }
        }
        return emptyList()
    }

    /** The video's own subtitle timed AutoSync: its starts go to DexWorld for devices that cannot read the file. */
    fun embedded(sourceKey: String, tracks: List<ReferenceTrack>) {
        val now = playing?.takeIf { it.streamUrl == sourceKey } ?: return
        val best = tracks.maxByOrNull { it.cues.size }?.takeIf { it.cues.size >= 30 } ?: return
        runCatching { DexWorldSync.sendTimeline(app, now, best.cues.map { it.startTimeMs }) }
    }

    private fun track(key: String, language: String?, cues: List<SubtitleSyncCue>, label: String, sampled: Boolean) =
        ReferenceTrack(key = key, language = language, cues = cues, label = label, sampled = sampled)

    private fun say(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
}
