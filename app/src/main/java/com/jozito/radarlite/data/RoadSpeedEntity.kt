package com.jozito.radarlite.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidade Room para armazenamento persistente dos limites de velocidade das vias (Road Speed Limits).
 *
 * Suporta indexação geoespacial por coordenadas e particionamento pelos 4 estados (PR, SC, SP, MS).
 *
 * @param id Identificador único autogerado
 * @param latitude Coordenada de latitude
 * @param longitude Coordenada de longitude
 * @param speedLimit Limite regulamentado da via em km/h (coluna limit_kmh)
 * @param source Fonte dos dados (ex: OSM:class)
 * @param isEstimated Se a velocidade foi estimada heuristicamente (coluna estimated)
 * @param speedLimitLow Limite para veículos pesados / baixa velocidade (coluna limit_low_kmh)
 * @param bearing Sentido/azimute da via em graus (0° a 360°, coluna direction_deg)
 * @param state Sigla do estado federativo (PR, SC, SP ou MS)
 */
@Entity(
    tableName = "road_speeds",
    indices = [
        Index(value = ["latitude", "longitude"]),
        Index(value = ["state"])
    ]
)
data class RoadSpeedEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Int,
    val source: String? = null,
    val isEstimated: Boolean = false,
    val speedLimitLow: Int? = null,
    val bearing: Double? = null,
    val state: String? = null
)

fun RoadSpeedEntity.toPoint() = RoadSpeedPoint(
    latitude = latitude,
    longitude = longitude,
    speedLimit = speedLimit,
    source = source,
    isEstimated = isEstimated,
    speedLimitLow = speedLimitLow,
    bearing = bearing
)

fun RoadSpeedPoint.toEntity(state: String? = null) = RoadSpeedEntity(
    latitude = latitude,
    longitude = longitude,
    speedLimit = speedLimit,
    source = source,
    isEstimated = isEstimated,
    speedLimitLow = speedLimitLow,
    bearing = bearing,
    state = state
)
