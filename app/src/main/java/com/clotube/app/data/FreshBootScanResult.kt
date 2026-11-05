package com.clotube.app.data

import com.clotube.app.model.Movie
import com.clotube.app.model.Series

data class FreshBootScanResult(
    val movies: List<Movie>,
    val series: List<Series>
)

