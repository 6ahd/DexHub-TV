package com.nuvio.tv.dexhub.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsCategory
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsJumpScroll
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSectionLabel
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.SubtitleFontDialog
import com.nuvio.tv.ui.screens.settings.subtitleFontLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.getFontFamily
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DexHubSettingsDeps {
    fun themeDataStore(): ThemeDataStore
    fun playerSettingsDataStore(): PlayerSettingsDataStore
}

/**
 * Settings > Dex Hub (DEXHUB.md): every Dex Hub feature in one place, first in the settings rail. The same rows
 * stay where Arc's own settings have them (subtitles, sources, layout); this page is where to find them all.
 */
@Composable
internal fun DexHubSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deps = remember { EntryPointAccessors.fromApplication(context.applicationContext, DexHubSettingsDeps::class.java) }
    val appFont by deps.themeDataStore().selectedFont.collectAsState(initial = AppFont.INTER)
    val player by deps.playerSettingsDataStore().playerSettings.collectAsState(initial = null)
    val themeMusic by com.nuvio.tv.dexhub.thememusic.DexHubThemeMusicPrefs.enabled(context).collectAsStateWithLifecycle()
    val flixSources = com.nuvio.tv.dexhub.sources.rememberFlixSources()
    var appFontDialog by remember { mutableStateOf(false) }
    var subtitleFontDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    SettingsJumpScroll(listState, SettingsCategory.DEXHUB)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.dexhub_settings_title),
            subtitle = stringResource(R.string.dexhub_settings_sub)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "subs") { SettingsSectionLabel(text = stringResource(R.string.dexhub_settings_subtitles)) }
                item(key = "ai") {
                    com.nuvio.tv.dexhub.ai.AiTranslationRow(
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                }
                item(key = "subfont") {
                    SettingsActionRow(
                        title = stringResource(R.string.dexhub_settings_subtitle_font),
                        subtitle = stringResource(R.string.dexhub_settings_subtitle_font_sub),
                        value = player?.subtitleStyle?.let { subtitleFontLabel(it) },
                        onClick = { subtitleFontDialog = true }
                    )
                }
                item(key = "whisper") { com.nuvio.tv.dexhub.ai.WhisperSyncRow() }
                item(key = "share") { com.nuvio.tv.dexhub.sync.SyncShareRow() }

                item(key = "look") { SettingsSectionLabel(text = stringResource(R.string.dexhub_settings_look)) }
                item(key = "appfont") {
                    SettingsActionRow(
                        title = stringResource(R.string.dexhub_settings_app_font),
                        subtitle = stringResource(R.string.dexhub_settings_app_font_sub),
                        value = appFont.displayName,
                        onClick = { appFontDialog = true }
                    )
                }
                item(key = "music") {
                    SettingsToggleRow(
                        title = stringResource(R.string.dexhub_theme_music),
                        subtitle = stringResource(R.string.dexhub_theme_music_sub),
                        checked = themeMusic,
                        onToggle = { com.nuvio.tv.dexhub.thememusic.DexHubThemeMusicPrefs.set(context, !themeMusic) }
                    )
                }
                item(key = "interests") {
                    SettingsActionRow(
                        title = stringResource(R.string.dexhub_interests_again),
                        subtitle = stringResource(R.string.dexhub_interests_again_sub),
                        onClick = { com.nuvio.tv.dexhub.welcome.DexHubInterests.askAgain(context) }
                    )
                }

                item(key = "sources") { SettingsSectionLabel(text = stringResource(R.string.dexhub_settings_sources)) }
                item(key = "flix") {
                    SettingsToggleRow(
                        title = stringResource(R.string.dexhub_sources_flix),
                        subtitle = stringResource(R.string.dexhub_sources_flix_sub),
                        checked = flixSources,
                        onToggle = { com.nuvio.tv.dexhub.sources.DexHubSourcesStyle.set(context, !flixSources) }
                    )
                }
                item(key = "usenet") { com.nuvio.tv.dexhub.usenet.UsenetSettingsRow() }
            }
        }
    }

    if (appFontDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.dexhub_settings_app_font),
            options = AppFont.entries.map { SettingsPickerOption(it, it.displayName, titleFontFamily = getFontFamily(it)) },
            selectedValue = appFont,
            onOptionSelected = { font ->
                scope.launch { deps.themeDataStore().setFont(font) }
                appFontDialog = false
            },
            onDismiss = { appFontDialog = false },
            width = 420.dp,
            maxHeight = 360.dp
        )
    }
    val style = player?.subtitleStyle
    if (subtitleFontDialog && style != null) {
        SubtitleFontDialog(
            selected = style.font,
            selectedImportedId = style.importedFontId,
            onSelected = { font -> scope.launch { deps.playerSettingsDataStore().setSubtitleFont(font) } },
            onSelectedImported = { id -> scope.launch { deps.playerSettingsDataStore().setSubtitleImportedFont(id) } },
            onDismiss = { subtitleFontDialog = false }
        )
    }
}
