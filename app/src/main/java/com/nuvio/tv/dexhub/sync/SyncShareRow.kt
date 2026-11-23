package com.nuvio.tv.dexhub.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow

/** Playback > Subtitles > "Shared sync with DexWorld" (DEXHUB.md): on unless the viewer turns it off, as in Dex Hub. */
@Composable
fun SyncShareRow() {
    val context = LocalContext.current
    val on by DexWorldSync.enabledFlow(context).collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.dexhub_sync_share),
        subtitle = stringResource(R.string.dexhub_sync_share_sub),
        checked = on,
        onToggle = { DexWorldSync.setEnabled(context, !on) }
    )
}
