package com.nuvio.tv.core.dexpicks

import com.nuvio.tv.core.dexpicks.DexPicksCatalogProvider.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexPicksSeedTest {
    private val entries = listOf(
        Triple(100L, "movie", Seed("tt1", "Heat")),
        Triple(300L, "series", Seed("tt2", "Dark")),
        Triple(200L, "movie", Seed("tt3", "Ronin")),
        Triple(400L, "movie", Seed("tt4", "tt4")),
        Triple(500L, "episode", Seed("tt5", ""))
    )

    @Test
    fun newestFilmSkipsIdsUsedAsNames() {
        assertEquals("Ronin", DexPicksCatalogProvider.newestSeed(entries, series = false)?.title)
    }

    @Test
    fun newestSeriesSkipsBlankNames() {
        assertEquals("Dark", DexPicksCatalogProvider.newestSeed(entries, series = true)?.title)
    }

    @Test
    fun noHistoryMeansNoRow() {
        assertNull(DexPicksCatalogProvider.newestSeed(emptyList(), series = false))
    }
}
