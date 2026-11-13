package com.jozito.radarlite.data

/**
 * Ponto de velocidade regulamentada da via pública (Road Speed Limit).
 *
 * Mapeado a partir de bases viárias (ex: OpenStreetMap / OSM:class).
 *
 * @param latitude Coordenada de latitude da via
 * @param longitude Coordenada de longitude da via
 * @param speedLimit Limite regulamentado da via em km/h (coluna limit_kmh)
 * @param source Fonte dos dados (ex: OSM:class)
 * @param isEstimated Se a velocidade foi estimada heuristicamente (coluna estimated: 1 ou 0)
 * @param speedLimitLow Limite inferior / baixa velocidade para veículos pesados (coluna limit_low_kmh)
 * @param bearing Sentido/azimute da via em graus (0° a 360°, coluna direction_deg)
 */
data class RoadSpeedPoint(
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Int,
    val source: String? = null,
    val isEstimated: Boolean = false,
    val speedLimitLow: Int? = null,
    val bearing: Double? = null
)
