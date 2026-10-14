package com.tvshowtracker.app.data

enum class WatchStatus(val label: String) {
    WATCHLIST("Watchlist"),
    WATCHING("Watching"),
    COMPLETED("Completed"),
    DROPPED("Dropped");

    companion object {
        fun fromStoredValue(value: String?): WatchStatus? =
            entries.firstOrNull { it.name == value }
    }
}

data class TvShow(
    val id: Int,
    val name: String,
    val summary: String? = null,
    val rating: Double? = null,
    val ratingVotes: Int? = null,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val premiered: String? = null,
    val ended: String? = null,
    val status: String? = null,
    val network: String? = null,
    val runtime: Int? = null,
    val officialSite: String? = null,
    val url: String? = null,
)

data class TvEpisode(
    val id: Int,
    val name: String,
    val season: Int?,
    val number: Int?,
    val airDate: String? = null,
    val summary: String? = null,
)

data class TrackedShow(
    val show: TvShow,
    val status: WatchStatus,
    val watchedEpisodes: Int,
)

enum class AppTab(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("My shows"),
}

data class AppState(
    val activeTab: AppTab = AppTab.HOME,
    val popularShows: List<TvShow> = emptyList(),
    val trendingShows: List<TvShow> = emptyList(),
    val topRatedShows: List<TvShow> = emptyList(),
    val mostRatedShows: List<TvShow> = emptyList(),
    val isLoadingHome: Boolean = false,
    val homeError: String? = null,
    val savedShows: List<TrackedShow> = emptyList(),
    val libraryFilter: WatchStatus? = null,
    val searchQuery: String = "",
    val searchResults: List<TvShow> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val selectedShow: TvShow? = null,
    val selectedStatus: WatchStatus? = null,
    val episodes: List<TvEpisode> = emptyList(),
    val watchedEpisodeIds: Set<Int> = emptySet(),
    val isLoadingDetails: Boolean = false,
    val detailError: String? = null,
)
