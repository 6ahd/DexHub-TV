package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dex Hub's Movies and Series tabs: the same Home, holding only that type's
 * rows (homeui/window.py NAV_KEYS 'movie' and 'series'). Null is the full Home.
 */
object DexHomeTypeFilter {
    const val MOVIE = "movie"
    const val SERIES = "series"

    /** Pseudo routes the top bar uses for the two tabs; both open Home. */
    const val MOVIES_ROUTE = "dex_tab_movies"
    const val SERIES_ROUTE = "dex_tab_series"

    private val current = MutableStateFlow<String?>(null)
    val type: StateFlow<String?> = current

    fun set(type: String?) {
        current.value = type
    }

    fun routeFor(type: String?): String? = when (type) {
        MOVIE -> MOVIES_ROUTE
        SERIES -> SERIES_ROUTE
        else -> null
    }

    fun typeFor(route: String?): String? = when (route) {
        MOVIES_ROUTE -> MOVIE
        SERIES_ROUTE -> SERIES
        else -> null
    }

    fun matches(filter: String, rawType: String?): Boolean {
        val raw = rawType?.trim()?.lowercase() ?: return false
        return when (filter) {
            SERIES -> raw == "series" || raw == "tv" || raw == "show" || raw == "anime"
            else -> raw == filter
        }
    }
}

private fun ContinueWatchingItem.contentTypeForFilter(): String = when (this) {
    is ContinueWatchingItem.InProgress -> progress.contentType
    is ContinueWatchingItem.NextUp -> info.contentType
}

/** Keeps only the chosen type's rows; collections belong to the full Home. */
internal fun ModernHomePresentationInput.filteredForDexType(): ModernHomePresentationInput {
    val filter = typeFilter ?: return this
    fun keep(item: ContinueWatchingItem) = DexHomeTypeFilter.matches(filter, item.contentTypeForFilter())
    return copy(
        homeRows = homeRows.filter { row ->
            when (row) {
                is HomeRow.Catalog -> DexHomeTypeFilter.matches(filter, row.row.rawType)
                is HomeRow.PlaceholderCatalog -> DexHomeTypeFilter.matches(filter, row.apiType)
                is HomeRow.CollectionRow -> false
            }
        },
        catalogRows = catalogRows.filter { DexHomeTypeFilter.matches(filter, it.rawType) },
        continueWatchingItems = continueWatchingItems.filter(::keep),
        upcomingItems = upcomingItems.filter(::keep),
        comingUpItems = comingUpItems.filter(::keep)
    )
}
