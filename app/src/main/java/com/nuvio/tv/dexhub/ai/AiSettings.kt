@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.dexhub.ui.DexHubDialogButton
import com.nuvio.tv.dexhub.ui.DexHubField
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Playback > Subtitles > "Translate with" (DEXHUB.md): Google Translate, or the viewer's own AI service. */
@Composable
fun AiTranslationRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val choice by AiService.choice(context).collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = stringResource(R.string.dexhub_ai_translate),
        subtitle = stringResource(R.string.dexhub_ai_translate_sub),
        value = if (choice.usable) choice.label else "Google Translate",
        onClick = { open = true },
        modifier = modifier
    )
    if (open) ServicesDialog(onDismiss = { open = false })
}

/**
 * Playback > Subtitles > "Sync by the film's speech" (DEXHUB.md): AutoSync may send a few minutes of the film's
 * sound to Whisper, with the viewer's Groq or OpenAI key, when the video has no subtitle to time by.
 */
@Composable
fun WhisperSyncRow() {
    val context = LocalContext.current
    val on by AiService.whisperFlow(context).collectAsStateWithLifecycle()
    val choice by AiService.choice(context).collectAsStateWithLifecycle()
    val hasKey = remember(choice) { AiService.keyFor(context, "groq").isNotBlank() || AiService.keyFor(context, "openai").isNotBlank() }
    com.nuvio.tv.ui.screens.settings.SettingsToggleRow(
        title = stringResource(R.string.dexhub_whisper_sync),
        subtitle = stringResource(if (hasKey) R.string.dexhub_whisper_sync_sub else R.string.dexhub_whisper_sync_no_key),
        checked = on && hasKey,
        enabled = hasKey,
        onToggle = { AiService.setWhisper(context, !on) }
    )
}

/** The services and their keys, from anywhere (the player's subtitles panel opens it too). */
@Composable
fun AiServicesDialog(onDismiss: () -> Unit) = ServicesDialog(onDismiss)

@Composable
private fun ServicesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choice by AiService.choice(context).collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AiService.Provider?>(null) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dexhub_ai_translate),
        subtitle = stringResource(R.string.dexhub_ai_services_sub),
        width = 720.dp
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SettingsActionRow(
                    title = "Google Translate",
                    subtitle = stringResource(R.string.dexhub_ai_google_sub),
                    value = if (!choice.usable) stringResource(R.string.dexhub_ai_in_use) else null,
                    onClick = { AiService.save(context, choice.copy(service = "google")); onDismiss() },
                    trailingIcon = null,
                    jumpAnchor = false
                )
            }
            item { SettingsSectionLabel(text = stringResource(R.string.dexhub_ai_your_key)) }
            items(AiService.providers, key = { it.id }) { p ->
                SettingsActionRow(
                    title = p.name,
                    subtitle = p.keysAt,
                    value = if (choice.usable && choice.service == p.id) stringResource(R.string.dexhub_ai_in_use) else null,
                    onClick = { editing = p },
                    jumpAnchor = false
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DexHubDialogButton(stringResource(R.string.action_close), onDismiss)
        }
    }
    editing?.let { p -> KeyDialog(p, onDone = { editing = null; onDismiss() }, onDismiss = { editing = null }) }
}

@Composable
private fun KeyDialog(provider: AiService.Provider, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = AiService.current(context)
    val same = now.service == provider.id
    var key by remember { mutableStateOf(AiService.keyFor(context, provider.id)) }
    var model by remember { mutableStateOf(if (same && now.model.isNotEmpty()) now.model else provider.models.first()) }
    var testing by remember { mutableStateOf(false) }
    val works = stringResource(R.string.dexhub_ai_works)
    NuvioDialog(
        onDismiss = onDismiss,
        title = provider.name,
        subtitle = stringResource(R.string.dexhub_ai_key_hint, provider.keysAt),
        width = 720.dp
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DexHubField(stringResource(R.string.dexhub_usenet_api_key), key, { key = it.trim() }, secret = true) }
            item { SettingsSectionLabel(text = stringResource(R.string.dexhub_ai_model)) }
            items(provider.models, key = { it }) { m ->
                SettingsActionRow(
                    title = m,
                    subtitle = null,
                    value = if (m == model) stringResource(R.string.dexhub_ai_chosen) else null,
                    onClick = { model = m },
                    trailingIcon = null,
                    jumpAnchor = false
                )
            }
            item { DexHubField(stringResource(R.string.dexhub_ai_other_model), model, { model = it.trim() }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DexHubDialogButton(stringResource(R.string.action_cancel), onDismiss)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            DexHubDialogButton(
                stringResource(if (testing) R.string.dexhub_ai_testing else R.string.action_save),
                primary = true,
                onClick = {
                    if (testing || key.isBlank() || model.isBlank()) return@DexHubDialogButton
                    val choice = AiService.Choice(provider.id, key, model)
                    testing = true
                    scope.launch {
                        // one short question first, so a wrong key or model is said now and not in the player
                        val error = withContext(Dispatchers.IO) {
                            runCatching { AiService.chat(choice, "Answer with the single word OK.", "OK?") }.exceptionOrNull()
                        }
                        testing = false
                        if (error == null) {
                            AiService.save(context, choice)
                            Toast.makeText(context, works, Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            val status = (error as? AiService.AiException)?.status ?: 0
                            val text = if (status == 401 || status == 403) context.getString(R.string.dexhub_ai_refused, choice.label)
                            else context.getString(R.string.dexhub_ai_failed, error.message.orEmpty().take(120))
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
}
