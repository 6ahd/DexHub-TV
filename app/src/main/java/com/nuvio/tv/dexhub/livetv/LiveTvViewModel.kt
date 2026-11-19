package com.nuvio.tv.dexhub.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LiveTab { LIVE, MOVIES, SERIES }

data class LiveTvUiState(
    val sources: List<LiveSource> = emptyList(),
    val source: LiveSource? = null,
    val lineup: LiveLineup? = null,
    val tab: LiveTab = LiveTab.LIVE,
    val group: String? = null,
    val guide: XmltvGuide? = null,
    val favourites: Set<String> = emptySet(),
    val focused: LiveChannel? = null,
    val series: LiveSeries? = null,
    val episodes: List<LiveEpisode>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val adding: Boolean = false
) {
    /** The tabs this source has something for. */
    val tabs: List<LiveTab>
        get() = lineup?.let { l ->
            listOfNotNull(
                LiveTab.LIVE.takeIf { l.channels.isNotEmpty() },
                LiveTab.MOVIES.takeIf { l.movies.isNotEmpty() },
                LiveTab.SERIES.takeIf { l.series.isNotEmpty() }
            )
        }.orEmpty()

    /** The tab's groups; the channels' start with the favourites when there are any. */
    val groups: List<String>
        get() = when (tab) {
            LiveTab.LIVE -> (if (favourites.isNotEmpty()) listOf(FavouritesGroup) else emptyList()) + lineup?.groups.orEmpty()
            LiveTab.MOVIES -> lineup?.movieGroups.orEmpty()
            LiveTab.SERIES -> lineup?.seriesGroups.orEmpty()
        }

    val channels: List<LiveChannel>
        get() = lineup?.channels.orEmpty().filter {
            if (group == FavouritesGroup) it.id in favourites else group == null || it.group == group
        }

    val movies: List<LiveMovie> get() = lineup?.movies.orEmpty().filter { group == null || it.group == group }
    val shows: List<LiveSeries> get() = lineup?.series.orEmpty().filter { group == null || it.group == group }

    companion object {
        /** The favourites' group key; a source's own group names never start with a control character. */
        const val FavouritesGroup = "\u0001favourites"
    }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(private val repository: LiveTvRepository) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvUiState(sources = repository.sources.value))
    val state: StateFlow<LiveTvUiState> = _state
    private var loading: Job? = null
    private var opening: Job? = null

    init {
        viewModelScope.launch {
            repository.sources.collect { sources ->
                val current = _state.value.source?.takeIf { s -> sources.any { it.id == s.id } } ?: sources.firstOrNull()
                _state.update { it.copy(sources = sources, adding = it.adding || sources.isEmpty()) }
                if (current?.id != _state.value.source?.id || (current != null && _state.value.lineup == null)) {
                    select(current)
                }
            }
        }
    }

    fun select(source: LiveSource?, refresh: Boolean = false) {
        loading?.cancel()
        _state.update {
            it.copy(source = source, lineup = null, group = null, guide = null, focused = null, series = null,
                episodes = null, error = null, loading = source != null,
                favourites = source?.let(repository::favourites).orEmpty())
        }
        source ?: return
        loading = viewModelScope.launch {
            try {
                val lineup = repository.lineup(source, refresh)
                _state.update { state ->
                    val withLineup = state.copy(lineup = lineup, loading = false)
                    val tab = withLineup.tabs.firstOrNull() ?: LiveTab.LIVE
                    withLineup.copy(tab = tab, group = withLineup.copy(tab = tab).groups.firstOrNull())
                }
                if (lineup.channels.isNotEmpty()) {
                    val guide = repository.guide(source, lineup)
                    _state.update { if (it.source?.id == source.id) it.copy(guide = guide) else it }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(loading = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun selectTab(tab: LiveTab) = _state.update {
        if (it.tab == tab) it else it.copy(tab = tab, group = it.copy(tab = tab).groups.firstOrNull(), series = null, episodes = null)
    }

    fun selectGroup(group: String) = _state.update { it.copy(group = group) }

    fun focus(channel: LiveChannel) = _state.update { if (it.focused?.id == channel.id) it else it.copy(focused = channel) }

    fun toggleFavourite(channel: LiveChannel) {
        val source = _state.value.source ?: return
        val favourites = repository.toggleFavourite(source, channel.id)
        _state.update { state ->
            // leaving the favourites' group empty takes the viewer back to the first group
            val group = if (state.group == LiveTvUiState.FavouritesGroup && favourites.isEmpty()) state.lineup?.groups?.firstOrNull()
                else state.group
            state.copy(favourites = favourites, group = group)
        }
    }

    fun openSeries(series: LiveSeries) {
        val source = _state.value.source ?: return
        opening?.cancel()
        _state.update { it.copy(series = series, episodes = series.episodes.takeIf { e -> e.isNotEmpty() }) }
        if (series.episodes.isNotEmpty()) return
        opening = viewModelScope.launch {
            val episodes = runCatching { repository.episodes(source, series) }.getOrDefault(emptyList())
            _state.update { if (it.series?.id == series.id) it.copy(episodes = episodes) else it }
        }
    }

    fun closeSeries() {
        opening?.cancel()
        _state.update { it.copy(series = null, episodes = null) }
    }

    fun refresh() = select(_state.value.source, refresh = true)

    fun showAdd(show: Boolean) = _state.update { it.copy(adding = show) }

    fun addM3u(name: String, url: String, guideUrl: String) {
        if (url.isBlank()) return
        repository.addM3u(name, url, guideUrl)
        _state.update { it.copy(adding = false) }
        repository.sources.value.lastOrNull()?.let { select(it) }
    }

    fun addXtream(name: String, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank()) return
        repository.addXtream(name, server, username, password)
        _state.update { it.copy(adding = false) }
        repository.sources.value.lastOrNull()?.let { select(it) }
    }

    fun remove(source: LiveSource) = repository.remove(source)
}
