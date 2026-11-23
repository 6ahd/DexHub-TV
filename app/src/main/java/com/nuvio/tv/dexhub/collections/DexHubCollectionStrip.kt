package com.nuvio.tv.dexhub.collections

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.abs

private val PosterWidth = 112.dp
private const val Posters = 14

/**
 * A collection's own posters behind its page's heading, after Flix's collection page (Flix/App/FlixCollectionPage
 * .swift): slanted one way and the other, fanning in one after another when the page opens, then drifting slowly
 * from side to side, dimmed and fading out downwards. Nothing shows until the collection has posters.
 */
@Composable
fun DexHubCollectionStrip(posters: List<String>) {
    if (posters.isEmpty()) return
    val shown = remember(posters) { List(Posters) { posters[it % posters.size] } }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val drift by rememberInfiniteTransition(label = "stripDrift").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(38_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "stripDriftX"
    )
    val posterPx = with(LocalDensity.current) { PosterWidth.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clipToBounds()
            .graphicsLayer { alpha = 0.6f; compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0.2f to Color.Black, 0.95f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .wrapContentWidth(unbounded = true)
                .graphicsLayer { translationX = drift * posterPx * 0.9f },
            horizontalArrangement = Arrangement.spacedBy(PosterWidth * 0.12f)
        ) {
            shown.forEachIndexed { index, url ->
                val appear by animateFloatAsState(
                    targetValue = if (appeared) 1f else 0f,
                    animationSpec = tween(700, delayMillis = 35 * abs(index - Posters / 2)),
                    label = "stripPoster"
                )
                val even = index % 2 == 0
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .graphicsLayer {
                            rotationZ = appear * (if (even) -9f else 7f)
                            translationY = posterPx * 1.5f * (if (even) -0.22f else -0.38f) - (1f - appear) * posterPx * 0.6f
                            alpha = appear
                        }
                        .size(PosterWidth, PosterWidth * 1.5f)
                        .clip(RoundedCornerShape(PosterWidth * 0.08f))
                )
            }
        }
    }
}
