@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.dexhub.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

/**
 * The subtitle colours as Dex Hub for Kodi offers them (skinui/substyle.py, v5.10.451): ten ready colours (Arc's own),
 * then the chosen colour's shades, three darker and three lighter. Picked as swatches, as the Kodi player shows them.
 */
internal object SubtitleColours {
    data class Swatch(val argb: Int, val ar: String, val en: String) {
        val name: String get() = if (Locale.getDefault().language == "ar") ar else en
    }

    val text = listOf(
        Swatch(0xFFFFFFFF.toInt(), "أبيض", "White"), Swatch(0xFFD9D9D9.toInt(), "رمادي فاتح", "Light grey"),
        Swatch(0xFFFFD700.toInt(), "ذهبي", "Gold"), Swatch(0xFF00E5FF.toInt(), "سماوي", "Cyan"),
        Swatch(0xFFFF5C5C.toInt(), "مرجاني", "Coral"), Swatch(0xFF00FF88.toInt(), "أخضر", "Green"),
        Swatch(0xFFFFAD42.toInt(), "برتقالي", "Orange"), Swatch(0xFFFFF59D.toInt(), "أصفر فاتح", "Soft yellow"),
        Swatch(0xFFB3E5FC.toInt(), "أزرق فاتح", "Pale blue"), Swatch(0xFFE1BEE7.toInt(), "بنفسجي فاتح", "Lavender"),
    )
    val outline = listOf(
        Swatch(0xFF000000.toInt(), "أسود", "Black"), Swatch(0xFFFFFFFF.toInt(), "أبيض", "White"),
        Swatch(0xFF00E5FF.toInt(), "سماوي", "Cyan"), Swatch(0xFFFF5C5C.toInt(), "مرجاني", "Coral"),
        Swatch(0xFF212121.toInt(), "فحمي", "Charcoal"), Swatch(0xFF8E44AD.toInt(), "بنفسجي", "Purple"),
    )
    private val steps = listOf(-0.45f, -0.30f, -0.15f, 0f, 0.25f, 0.45f, 0.65f)
    private val stepNames = listOf("أغمق جدًا" to "Much darker", "أغمق" to "Darker", "أغمق قليلًا" to "A little darker",
        "الأصلي" to "As it is", "أفتح قليلًا" to "A little lighter", "أفتح" to "Lighter", "أفتح جدًا" to "Much lighter")

    /** The base colour's shades, mixed with black (darker) or white (lighter). */
    fun shades(base: Int): List<Swatch> = steps.mapIndexed { i, step ->
        val target = if (step > 0) 255 else 0
        val mix = kotlin.math.abs(step)
        fun ch(shift: Int): Int { val c = (base shr shift) and 0xFF; return (c + (target - c) * mix).toInt().coerceIn(0, 255) }
        val argb = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        Swatch(argb, stepNames[i].first, stepNames[i].second)
    }

    fun nameOf(argb: Int, list: List<Swatch>): String =
        list.firstOrNull { it.argb == argb }?.name ?: "#%06X".format(argb and 0xFFFFFF)
}

/** A row of colour swatches (several rows when given several), the chosen one ringed. */
@Composable
internal fun SwatchDialog(
    title: String,
    rows: List<Pair<String, List<SubtitleColours.Swatch>>>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var focusedName by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = focusedName.ifEmpty { null }, width = 720.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEachIndexed { r, (label, swatches) ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    swatches.forEachIndexed { i, s ->
                        val isSelected = s.argb == selected
                        Card(
                            onClick = { onPick(s.argb) },
                            modifier = Modifier.size(44.dp)
                                .then(if (r == 0 && (isSelected || (i == 0 && swatches.none { it.argb == selected }))) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedName = s.name },
                            shape = CardDefaults.shape(CircleShape),
                            colors = CardDefaults.colors(containerColor = Color(s.argb), focusedContainerColor = Color(s.argb)),
                            border = CardDefaults.border(
                                border = if (isSelected) Border(BorderStroke(3.dp, Color.White), shape = CircleShape)
                                    else Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), shape = CircleShape),
                                focusedBorder = Border(BorderStroke(3.dp, NuvioTheme.colors.Primary), shape = CircleShape)
                            ),
                            scale = CardDefaults.scale(focusedScale = 1.15f)
                        ) { Box(Modifier.size(44.dp)) }
                    }
                }
            }
        }
    }
}
