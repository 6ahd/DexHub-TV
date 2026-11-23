package com.nuvio.tv.dexhub.subtitles

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.downloadSubtitleBody
import com.nuvio.tv.ui.screens.player.selectAddonSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val Tag = "DexHubTranslate"
private val main = Handler(Looper.getMainLooper())

/**
 * The player's "Translate subtitle" (DEXHUB.md), after Dex Hub for Kodi's (subsync/transflow.py): the chosen add-on
 * subtitle, or an English one when the chosen one is already in the viewer's language or none is chosen, is read,
 * translated into the preferred subtitle language with [SubtitleTranslator] (from the line being shown onwards
 * first; by Google Translate, or the viewer's own AI service when one is set in Playback > Subtitles), kept on this device, and put on as a subtitle of its own named "Dex Hub". A subtitle translated once
 * opens at once the next time.
 */
internal fun translateSubtitle(controller: PlayerRuntimeController) = with(controller) {
    val state = _uiState.value
    val target = state.subtitleStyle.preferredLanguage.lowercase(Locale.ROOT).substringBefore('-')
        .takeIf { it.length in 2..3 && it != "none" && it != "off" } ?: Locale.getDefault().language
    fun inTarget(s: Subtitle) = s.lang.lowercase(Locale.ROOT).startsWith(target)
    val source = state.selectedAddonSubtitle?.takeIf { !inTarget(it) && !it.id.startsWith(IdPrefix) }
        ?: state.addonSubtitles.firstOrNull { it.lang.lowercase(Locale.ROOT).startsWith("en") }
        ?: state.addonSubtitles.firstOrNull { !inTarget(it) && !it.id.startsWith(IdPrefix) }
    if (source == null) {
        say(controller, context.getString(R.string.dexhub_translate_none))
        return@with
    }
    val choice = com.nuvio.tv.dexhub.ai.AiService.current(context).takeIf { it.usable }
    say(controller, context.getString(R.string.dexhub_translate_working_with, choice?.label ?: "Google Translate"))
    scope.launch {
        try {
            val folder = File(context.cacheDir, "subtitles/dexhub_translated").apply { mkdirs() }
            val with = choice?.let { "|${it.service}|${it.model}" }.orEmpty()
            val file = File(folder, "${(source.url + "|" + target + with).hashCode().toUInt()}_$target.srt")
            if (!file.exists() || file.length() == 0L) {
                val body = downloadSubtitleBody(source.url, source.lang, source.headers)
                val lines = withContext(Dispatchers.Default) { SubtitleTranslator.parse(body) }
                if (lines.isEmpty()) throw SubtitleTranslator.TranslateException("empty")
                val now = _playbackTimeline.value.currentPosition
                val first = lines.indexOfFirst { it.endMs >= now }.coerceAtLeast(0)
                val ai = choice?.let { c -> SubtitleTranslator.Ask { system, user -> com.nuvio.tv.dexhub.ai.AiService.chat(c, system, user) } }
                val language = Locale(target).getDisplayLanguage(Locale.ENGLISH).ifEmpty { target }
                val translated = SubtitleTranslator.translate(lines, target, first, ai = ai, language = language)
                withContext(Dispatchers.IO) { file.writeText(SubtitleTranslator.renderSrt(translated)) }
            }
            val made = Subtitle(
                id = IdPrefix + file.name,
                url = "file://" + file.absolutePath,
                lang = target,
                addonName = "Dex Hub · " + Locale(target).getDisplayLanguage(Locale(target)).ifEmpty { target },
                addonLogo = null
            )
            _uiState.update { s -> s.copy(addonSubtitles = listOf(made) + s.addonSubtitles.filterNot { it.id == made.id }) }
            selectAddonSubtitle(made)
            say(controller, context.getString(R.string.dexhub_translate_done))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(Tag, "translation failed", error)
            val status = (error as? com.nuvio.tv.dexhub.ai.AiService.AiException)?.status ?: 0
            val busy = (error as? SubtitleTranslator.TranslateException)?.reason == "busy"
            say(controller, when {
                status == 401 || status == 403 -> context.getString(R.string.dexhub_ai_refused, choice?.label.orEmpty())
                status == 429 -> context.getString(R.string.dexhub_ai_busy, choice?.label.orEmpty())
                busy -> context.getString(R.string.dexhub_translate_busy)
                else -> context.getString(R.string.dexhub_translate_failed)
            })
        }
    }
    Unit
}

private const val IdPrefix = "dexhub-translated:"

private fun say(controller: PlayerRuntimeController, text: String) {
    main.post { Toast.makeText(controller.context, text, Toast.LENGTH_SHORT).show() }
}
