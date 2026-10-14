package com.tvshowtracker.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tvshowtracker.app.data.AppState
import com.tvshowtracker.app.data.AppTab
import com.tvshowtracker.app.data.TvEpisode
import com.tvshowtracker.app.data.TvMazeApi
import com.tvshowtracker.app.data.TvShow
import com.tvshowtracker.app.data.WatchDatabase
import com.tvshowtracker.app.data.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

class ShowTrackerViewModel(
    private val api: TvMazeApi,
    private val database: WatchDatabase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState(isLoadingHome = true))
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshDiscovery()
        refreshLibrary()
    }

    fun selectTab(tab: AppTab) {
        mutableState.update { it.copy(activeTab = tab) }
    }

    fun setLibraryFilter(filter: WatchStatus?) {
        mutableState.update { it.copy(libraryFilter = filter) }
    }

    fun refreshDiscovery() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingHome = true, homeError = null) }
            val popularResult = runCatching {
                withContext(Dispatchers.IO) { api.popularShows() }
            }
            val trendingResult = runCatching {
                withContext(Dispatchers.IO) { api.trendingToday(LocalDate.now().toString()) }
            }

            val popular = popularResult.getOrDefault(emptyList())
            val trending = trendingResult.getOrDefault(emptyList())
            val errors = buildList {
                popularResult.exceptionOrNull()?.let {
                    add("Popular shows couldn't load. Check your connection and try again.")
                }
                trendingResult.exceptionOrNull()?.let {
                    add("Today's schedule couldn't load.")
                }
            }

            mutableState.update {
                it.copy(
                    popularShows = popular,
                    trendingShows = trending,
                    topRatedShows = popular
                        .filter { show -> show.rating != null }
                        .sortedByDescending { show -> show.rating }
                        .take(24),
                    mostRatedShows = popular
                        .filter { show -> show.ratingVotes != null }
                        .sortedByDescending { show -> show.ratingVotes }
                        .take(24),
                    isLoadingHome = false,
                    homeError = errors.joinToString(" ").ifBlank { null },
                )
            }
        }
    }

    fun searchShows(query: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(searchQuery = query, searchError = null) }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            mutableState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            mutableState.update { it.copy(isSearching = true, searchError = null) }
            try {
                val results = withContext(Dispatchers.IO) { api.searchShows(trimmed) }
                mutableState.update {
                    it.copy(searchResults = results, isSearching = false, searchError = null)
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        searchResults = emptyList(),
                        isSearching = false,
                        searchError = "Search isn't available right now. Check your connection and try again.",
                    )
                }
            }
        }
    }

    fun openShow(show: TvShow) {
        mutableState.update {
            it.copy(
                selectedShow = show,
                selectedStatus = null,
                episodes = emptyList(),
                watchedEpisodeIds = emptySet(),
                isLoadingDetails = true,
                detailError = null,
            )
        }

        viewModelScope.launch {
            val storedStatus = withContext(Dispatchers.IO) { database.statusFor(show.id) }
            mutableState.update { it.copy(selectedStatus = storedStatus) }
            try {
                val detailsAndEpisodes = withContext(Dispatchers.IO) {
                    api.showDetails(show.id) to api.episodes(show.id)
                }
                val watchedIds = withContext(Dispatchers.IO) {
                    database.watchedEpisodeIds(show.id)
                }
                mutableState.update {
                    it.copy(
                        selectedShow = detailsAndEpisodes.first,
                        episodes = detailsAndEpisodes.second,
                        watchedEpisodeIds = watchedIds,
                        isLoadingDetails = false,
                        detailError = null,
                    )
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isLoadingDetails = false,
                        detailError = "Show details couldn't load. Check your connection and retry.",
                    )
                }
            }
        }
    }

    fun closeShow() {
        mutableState.update {
            it.copy(
                selectedShow = null,
                selectedStatus = null,
                episodes = emptyList(),
                watchedEpisodeIds = emptySet(),
                isLoadingDetails = false,
                detailError = null,
            )
        }
    }

    fun setStatus(status: WatchStatus?) {
        val show = mutableState.value.selectedShow ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (status == null) {
                    database.removeShow(show.id)
                } else {
                    database.saveShow(show, status)
                }
            }
            mutableState.update { it.copy(selectedStatus = status) }
            refreshLibrary()
        }
    }

    fun toggleEpisode(episode: TvEpisode) {
        val show = mutableState.value.selectedShow ?: return
        val wasWatched = episode.id in mutableState.value.watchedEpisodeIds
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                var status = database.statusFor(show.id)
                if (!wasWatched && status in listOf(null, WatchStatus.WATCHLIST)) {
                    status = WatchStatus.WATCHING
                    database.saveShow(show, status)
                }
                database.setEpisodeWatched(show.id, episode.id, !wasWatched)
                database.watchedEpisodeIds(show.id) to status
            }
            mutableState.update {
                it.copy(
                    watchedEpisodeIds = stored.first,
                    selectedStatus = stored.second,
                )
            }
            refreshLibrary()
        }
    }

    private fun refreshLibrary() {
        viewModelScope.launch {
            val shows = withContext(Dispatchers.IO) { database.trackedShows() }
            mutableState.update { it.copy(savedShows = shows) }
        }
    }

    companion object {
        fun factory(api: TvMazeApi, database: WatchDatabase) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ShowTrackerViewModel::class.java))
                    return ShowTrackerViewModel(api, database) as T
                }
            }
    }
}
