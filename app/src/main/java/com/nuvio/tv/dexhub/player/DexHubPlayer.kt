package com.nuvio.tv.dexhub.player

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/**
 * The player in Dex Hub's look (DEXHUB.md), after skin.dexhub's player (Includes_DexPlayer): the title's logo
 * large, and under its facts a row as Dex Hub shows it, the IMDb rating and when the title ends. On by default;
 * Settings > Dex Hub turns it off for Arc's own.
 */
object DexHubPlayerLook {
    private const val Prefs = "dexhub_player"
    private val state = MutableStateFlow<Boolean?>(null)

    fun flow(context: Context): StateFlow<Boolean> {
        if (state.value == null) {
            state.value = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean("look", true)
        }
        @Suppress("UNCHECKED_CAST")
        return state.asStateFlow() as StateFlow<Boolean>
    }

    fun set(context: Context, on: Boolean) {
        state.value = on
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putBoolean("look", on).apply()
    }
}

/** What the player knows of the playing title beyond its own state: its IMDb rating, from the title's details. */
object DexHubPlayerMeta {
    val imdb = mutableStateOf<Pair<String, Float>?>(null)

    fun set(id: String?, rating: Float?) {
        imdb.value = if (id != null && rating != null && rating > 0f) id to rating else null
    }
}

@Composable
internal fun dexHubPlayerLook(): Boolean {
    val context = LocalContext.current
    val on by DexHubPlayerLook.flow(context).collectAsState()
    return on
}

/** IMDb ★ · Ends at 10:50 PM · 1.25×, under the title's facts. */
@Composable
internal fun DexHubPlayerInfoRow(viewModel: PlayerViewModel, state: PlayerUiState, contentId: String?) {
    val context = LocalContext.current
    val timeline by viewModel.playbackTimeline.collectAsState()
    val rating = DexHubPlayerMeta.imdb.value?.takeIf { it.first == contentId }?.second
    val left = (timeline.duration - timeline.currentPosition).coerceAtLeast(0L)
    val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
    val endsAt = if (timeline.duration > 0 && !timeline.isLive) {
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (left / speed).toLong()))
    } else null
    if (rating == null && endsAt == null) return
    val muted = Color.White.copy(alpha = 0.78f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (rating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text("IMDb", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
                Text("%.1f".format(rating), color = muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (endsAt != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.dexhub_player_ends_at, endsAt), color = muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (speed != 1f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                Text("%.2f×".format(speed).replace(".00", ""), color = muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
