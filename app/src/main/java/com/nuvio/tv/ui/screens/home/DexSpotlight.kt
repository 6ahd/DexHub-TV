package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R

/** skin.dexhub spotlight (list 4000): up to 8 titles, fed here by the first catalog row. */
internal const val DEX_SPOTLIGHT_COUNT = 8

/** Carousel step while the spotlight holds focus (skin §2.6). */
internal const val DEX_SPOTLIGHT_ADVANCE_MS = 10_000L

/** Whether Home was last left on the spotlight, and on which title, so a return lands there again. */
internal object DexSpotlightMemory {
    var active: Boolean = true
    var index: Int = 0
}

/** The row the spotlight takes its titles from: the first one made of catalog titles. */
internal fun dexSpotlightRow(rows: List<HeroCarouselRow>): HeroCarouselRow? = rows.firstOrNull { row ->
    row.key != MODERN_CONTINUE_WATCHING_ROW_KEY && row.key != MODERN_COMING_UP_ROW_KEY &&
        row.items.list.count { item ->
            (item.payload as? ModernPayload.Catalog)?.itemId?.startsWith("__placeholder_") == false
        } >= 2
}

internal fun HeroCarouselRow.dexSpotlightItems(): List<ModernCarouselItem> = items.list.filter { item ->
    (item.payload as? ModernPayload.Catalog)?.itemId?.startsWith("__placeholder_") == false
}.take(DEX_SPOTLIGHT_COUNT)

/**
 * Hero state A of skin.dexhub: Play / More info / Trailer capsules (list 4010, drawn at 84 %),
 * and under them one dot per spotlight title with the source row's name beside them.
 */
@Composable
internal fun DexSpotlightControls(
    count: Int,
    activeIndex: () -> Int,
    sourceLabel: String?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onTrailer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.5.dp)) {
            DexSpotlightButton(Icons.Filled.PlayArrow, stringResource(R.string.dex_spotlight_play), onPlay,
                Modifier.focusRequester(playFocusRequester))
            DexSpotlightButton(Icons.Filled.Info, stringResource(R.string.dex_spotlight_more_info), onMoreInfo)
            DexSpotlightButton(Icons.Filled.Videocam, stringResource(R.string.dex_spotlight_trailer), onTrailer)
        }
        // Page dots at x 96, y 900 px; the buttons sit at y 836.
        Row(
            modifier = Modifier.padding(start = 6.dp, top = 3.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val current = activeIndex()
            repeat(count) { index ->
                val active = index == current
                Box(
                    Modifier
                        .padding(end = 5.dp)
                        .width(if (active) 17.dp else 6.dp)
                        .height(4.dp)
                        .background(if (active) Color.White else Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                )
            }
            sourceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** A 270 x 68 px capsule at 84 %: glass at rest, white with dark text when focused. */
@Composable
private fun DexSpotlightButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val content = if (focused) Color(0xFF272727) else Color.White
    Row(
        modifier = modifier
            .height(28.5.dp)
            .widthIn(min = 113.dp)
            .background(if (focused) Color(0xF2FFFFFF) else Color(0xB3161B22), shape)
            .border(1.dp, if (focused) Color.Transparent else Color(0x2EFFFFFF), shape)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick(); true
                } else false
            }
            .focusable(interactionSource = interaction)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        Text(
            text = label,
            fontSize = 11.8.sp,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
