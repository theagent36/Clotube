package com.clotube.app.viewmodel

enum class NavigationTab(val title: String) {
    LIBRARY("Library"),
    RECENT("Recent"),
    SETTINGS("Settings")
}

typealias MediaLibraryViewModel = VideoViewModel
typealias MediaLibraryUiState = VideoUiState
