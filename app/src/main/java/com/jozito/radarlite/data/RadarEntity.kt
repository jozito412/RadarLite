package com.jozito.radarlite.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "radars",
    indices = [
        Index(value = ["latitude", "longitude"])
    ]
)
data class RadarEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Int,
    val type: String,
    val bearing: Double? = null,
    val source: String? = null
)
