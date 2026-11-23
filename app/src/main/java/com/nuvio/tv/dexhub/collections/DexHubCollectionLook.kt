package com.nuvio.tv.dexhub.collections

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The collections' look (DEXHUB.md), as skin.dexhub draws a collection: the focused title's backdrop, logo, facts
 * and story at the top and the collection's rows under it, the collection's own mark in the corner. That is Arc's
 * "follow the Home layout" view, so a collection set to the tab grid (Arc's default) opens in it while this is on.
 * A collection set to rows keeps rows.
 */
object DexHubCollectionLook {
    private const val Prefs = "dexhub_look"
    private const val Key = "collections_dexhub"
    private val state = MutableStateFlow<Boolean?>(null)

    fun flow(context: Context): StateFlow<Boolean> {
        if (state.value == null) {
            state.value = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).getBoolean(Key, true)
        }
        @Suppress("UNCHECKED_CAST")
        return state.asStateFlow() as StateFlow<Boolean>
    }

    fun set(context: Context, on: Boolean) {
        state.value = on
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().putBoolean(Key, on).apply()
    }

    /** The view a collection opens in. */
    fun viewMode(context: Context, saved: FolderViewMode): FolderViewMode =
        if (saved == FolderViewMode.TABBED_GRID && flow(context).value) FolderViewMode.FOLLOW_LAYOUT else saved
}

/** The collection's mark at the top corner: its logo or picture, else its emoji and name, on a soft glass chip. */
@Composable
fun DexHubCollectionMark(folder: CollectionFolder, modifier: Modifier = Modifier) {
    val logo = folder.titleLogoUrl?.takeIf { it.isNotBlank() }
    val cover = logo ?: folder.coverImageUrl?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = folder.title,
                modifier = Modifier
                    .height(if (logo == null && folder.tileShape == PosterShape.POSTER) 44.dp else 30.dp)
                    .widthIn(max = 150.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit
            )
        } else if (!folder.coverEmoji.isNullOrBlank()) {
            Text(text = folder.coverEmoji.orEmpty(), style = MaterialTheme.typography.titleLarge)
        }
        if (cover == null || (logo == null && folder.tileShape == PosterShape.POSTER)) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
