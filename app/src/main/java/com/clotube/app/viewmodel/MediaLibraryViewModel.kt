package com.clotube.app.viewmodel

@androidx.annotation.Keep
enum class NavigationTab(val title: String) {
    LIBRARY("Library"),
    SEARCH("Search"),
    WATCHLIST("Watchlist"),
    RECENT("Recent"),
    SETTINGS("Settings")
}

typealias MediaLibraryViewModel = VideoViewModel
typealias MediaLibraryUiState = VideoUiState
