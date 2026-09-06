package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo

/** Урезанная строка `geo_points` — только то, что нужно нарисовать линию. */
data class RoutePointProjection(
    @ColumnInfo(name = "lat")
    val latitude: Double,
    @ColumnInfo(name = "lon")
    val longitude: Double,
)
