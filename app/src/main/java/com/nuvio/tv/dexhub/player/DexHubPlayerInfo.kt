package com.nuvio.tv.dexhub.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.player.PlaybackStatsSample
import com.nuvio.tv.ui.screens.player.StatsDot
import com.nuvio.tv.ui.screens.player.StatsGroup
import com.nuvio.tv.ui.screens.player.StatsRow

/**
 * The player's information as Lucent and skin.dexhub draw it (DEXHUB.md; Lucent's Player info, skin.dexhub's
 * OSD_PPI_VS10): a large glass panel over the picture, the file's name and chips at the top, tiles on the left
 * (picture, HDR, sound, bitrate, buffer, system) and the details in two columns (source and video; audio and
 * network). Made from the same live sample as Arc's stats HUD, so it shows what Arc measures.
 */
@Composable
internal fun DexHubPlayerInfoPanel(visible: Boolean, sample: PlaybackStatsSample?, title: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible && sample != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val s = sample ?: return@AnimatedVisibility
        val rows = s.sections.flatMap { it.rows }
        fun row(label: String): StatsRow? = rows.firstOrNull { it.label == label }
        val video = row("Video")?.value.orEmpty()
        val height = Regex("""\d{3,4}\s*[x×]\s*(\d{3,4})""").find(video)?.groupValues?.get(1)?.toIntOrNull()
        val resolution = when {
            height == null -> null
            height >= 2000 -> "2160p"; height >= 1000 -> "1080p"; height >= 700 -> "720p"; height >= 560 -> "576p"
            else -> "${height}p"
        }
        val hdr = row("HDR")?.value?.takeIf { it.isNotBlank() } ?: "SDR"
        val audio = row("Audio")?.value.orEmpty()
        val chips = listOfNotNull(resolution, hdr.takeUnless { it == "SDR" }?.substringBefore(' '),
            Regex("""(?i)\b(HEVC|H\.?265|H\.?264|AVC|AV1|VP9)\b""").find(video)?.value?.uppercase(),
            Regex("""(?i)\b(TrueHD|Atmos|E-?AC-?3|DDP|AC-?3|DTS(?:-HD)?(?: MA)?|AAC|FLAC|Opus)\b""").find(audio)?.value,
            Regex("""\b([1-9]\.[01])\b""").find(audio)?.value)

        Column(
            Modifier
                .width(880.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xE6141A2A), Color(0xEB0D1220))),
                    RoundedCornerShape(22.dp)
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // the file and its chips
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PLAYER INFO", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        row("File")?.value?.substringBefore(" · ") ?: title.orEmpty(),
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEachIndexed { i, chip -> Chip(chip, solid = i == 0) }
                    }
                }
                Text(s.engineLabel, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // tiles
                Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("PICTURE", resolution ?: "—", video.substringAfter(" · ", "").take(34))
                    Tile("HDR", hdr.substringBefore(" · "), hdr.substringAfter(" · ", "").take(34))
                    Tile("SOUND", Regex("""\b([1-9]\.[01])\b""").find(audio)?.value ?: audio.substringBefore(" · ").take(14).ifEmpty { "—" },
                        audio.substringAfter(" · ", "").take(34))
                    Tile("BITRATE", (row("V bitrate") ?: row("File bitrate"))?.value ?: "—", null)
                    Tile("BUFFER", row("Buffer")?.value ?: "—", null, dot = row("Buffer")?.dot)
                    Tile("SYSTEM", row("App CPU")?.value?.let { "CPU $it" } ?: "—", row("Memory")?.value, dot = row("App CPU")?.dot)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section("SOURCE", s, StatsGroup.SOURCE)
                    Section("VIDEO", s, StatsGroup.VIDEO)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section("AUDIO", s, StatsGroup.AUDIO)
                    Section("NETWORK", s, StatsGroup.NETWORK)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, solid: Boolean) {
    Box(
        Modifier
            .background(if (solid) Color.White else Color.White.copy(alpha = 0.14f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = if (solid) Color(0xFF0D1220) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun dotColour(dot: StatsDot?): Color? = when (dot) {
    StatsDot.GOOD -> Color(0xFF3DDC84)
    StatsDot.WARN -> Color(0xFFFFB020)
    StatsDot.BAD -> Color(0xFFFF5C5C)
    else -> null
}

@Composable
private fun Tile(label: String, big: String, small: String?, dot: StatsDot? = null) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dotColour(dot)?.let { Box(Modifier.size(6.dp).background(it, CircleShape)) }
            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(big, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!small.isNullOrBlank()) Text(small, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun Section(header: String, sample: PlaybackStatsSample, group: StatsGroup) {
    val rows = sample.sections.firstOrNull { it.group == group }?.rows.orEmpty()
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(header, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        rows.take(9).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    dotColour(r.dot)?.let { Box(Modifier.size(5.dp).background(it, CircleShape)) }
                    Text(r.label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                }
                Text(r.value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}
