@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

/**
 * Every Dex Hub page's focusable card (DEXHUB.md), drawn as Arc's own cards are: under Arc's v2 interface the
 * focus edge, glow and glass of its design system (nuvioV2Focus, nuvioGlass, as the Calendar's cards), else the
 * theme's focus ring. [selectedBorder] marks a chosen card (a picked interest) apart from the focused one.
 */
@Composable
fun DexHubCard(
    onClick: () -> Unit,
    shape: Shape,
    colors: CardColors,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    scale: CardScale = CardDefaults.scale(focusedScale = 1.04f),
    focusWidth: Dp = 2.dp,
    selectedBorder: Border? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    onFocus?.invoke(it.isFocused)
                }
            }
            .nuvioV2Focus(focused, shape, stationary = true)
            .then(if (v2 && focused) Modifier.nuvioGlass(GlassRole.CONTROL, focused = true, shape = shape) else Modifier),
        shape = CardDefaults.shape(shape),
        colors = colors,
        border = CardDefaults.border(
            border = selectedBorder ?: Border.None,
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(focusWidth), shape = shape)
        ),
        scale = scale,
        content = content
    )
}
