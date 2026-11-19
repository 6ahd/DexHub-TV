package com.nuvio.tv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dex Hub TV (DEXHUB.md): the shape of the portrait posters, as their height in percent of their width: 150 is Arc's
 * 2:3, 133 a wider 3:4, 100 a square. Kept on this device and read by [rememberPosterCardStyle], so every screen that
 * sizes its posters from the chosen width takes it without its own setting.
 */
object DexHubPosterShape {
    const val CLASSIC = 150
    const val WIDE = 133
    const val SQUARE = 100
    private val shapes = setOf(CLASSIC, WIDE, SQUARE)
    private const val PREFS = "dexhub_look"
    private const val KEY = "poster_height_percent"

    private val state = MutableStateFlow(CLASSIC)
    @Volatile private var loaded = false

    fun heightPercent(context: Context): StateFlow<Int> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = prefs(context).getInt(KEY, CLASSIC).takeIf { it in shapes } ?: CLASSIC
                loaded = true
            }
        }
        return state
    }

    fun set(context: Context, heightPercent: Int) {
        val value = heightPercent.takeIf { it in shapes } ?: CLASSIC
        prefs(context).edit().putInt(KEY, value).apply()
        loaded = true
        state.value = value
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The chosen poster shape, as [DexHubPosterShape] percent; follows a change at once. */
@Composable
fun rememberPosterHeightPercent(): Int {
    val context = LocalContext.current
    val shape by remember(context) { DexHubPosterShape.heightPercent(context) }.collectAsState()
    return shape
}
