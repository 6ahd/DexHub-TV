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

data class LiveTvUiState(
    val sources: List<LiveSource> = emptyList(),
    val source: LiveSource? = null,
    val lineup: LiveLineup? = null,
    val group: String? = null,
    val guide: XmltvGuide? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val adding: Boolean = false
) {
    /** The group's channels, by number where the source numbers them. */
    val channels: List<LiveChannel>
        get() = lineup?.channels.orEmpty().filter { group == null || it.group == group }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(private val repository: LiveTvRepository) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvUiState(sources = repository.sources.value))
    val state: StateFlow<LiveTvUiState> = _state
    private var loading: Job? = null

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
        _state.update { it.copy(source = source, lineup = null, group = null, guide = null, error = null, loading = source != null) }
        source ?: return
        loading = viewModelScope.launch {
            try {
                val lineup = repository.lineup(source, refresh)
                _state.update { it.copy(lineup = lineup, group = lineup.groups.firstOrNull(), loading = false) }
                val guide = repository.guide(source, lineup)
                _state.update { if (it.source?.id == source.id) it.copy(guide = guide) else it }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(loading = false, error = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun selectGroup(group: String) = _state.update { it.copy(group = group) }

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
