package com.jozito.radarlite.data

import com.jozito.radarlite.service.RadarOverlayService
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Gerenciador em memória da base de velocidades regulamentadas das vias (Road Speed Limits).
 *
 * Fornece suporte para identificação da velocidade da via onde o veículo está trafegando,
 * permitindo o desempate preciso entre vias expressas e pistas marginais paralelas (Desafio 1 - Proposta 2).
 */
class RoadSpeedManager {

    private val roadPoints = mutableListOf<RoadSpeedPoint>()

    /**
     * Carrega pontos de velocidade a partir de um InputStream de CSV formatado como:
     * lat,lng,limit_kmh,source,estimated,limit_low_kmh,direction_deg
     */
    fun loadCsv(inputStream: InputStream): Int {
        val loaded = mutableListOf<RoadSpeedPoint>()
        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            var isFirst = true
            for (line in lines) {
                if (isFirst) {
                    isFirst = false
                    if (line.startsWith("lat", ignoreCase = true)) continue
                }
                parseCsvLine(line)?.let { loaded.add(it) }
            }
        }
        synchronized(roadPoints) {
            roadPoints.clear()
            roadPoints.addAll(loaded)
        }
        return loaded.size
    }

    /**
     * Faz o parse de uma única linha CSV de velocidade de via.
     */
    fun parseCsvLine(line: String): RoadSpeedPoint? {
        val parts = line.split(",")
        if (parts.size < 3) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        val limit = parts[2].trim().toIntOrNull() ?: return null
        val source = parts.getOrNull(3)?.trim()?.ifEmpty { null }
        val estimated = parts.getOrNull(4)?.trim() == "1"
        val limitLow = parts.getOrNull(5)?.trim()?.toIntOrNull()
        val bearing = parts.getOrNull(6)?.trim()?.toDoubleOrNull()

        return RoadSpeedPoint(
            latitude = lat,
            longitude = lng,
            speedLimit = limit,
            source = source,
            isEstimated = estimated,
            speedLimitLow = limitLow,
            bearing = bearing
        )
    }

    /**
     * Encontra a velocidade da via correspondente à posição e sentido atual do veículo.
     * Retorna o ponto mais próximo dentro de um raio de matching (padrão 50m) com sentido compatível.
     */
    fun findMatchingRoadSpeed(
        vehicleLat: Double,
        vehicleLon: Double,
        vehicleBearing: Float? = null,
        maxDistanceMeters: Double = 50.0
    ): RoadSpeedPoint? {
        synchronized(roadPoints) {
            if (roadPoints.isEmpty()) return null

            return roadPoints.mapNotNull { point ->
                val dist = RadarOverlayService.calculateDistanceMeters(
                    vehicleLat, vehicleLon, point.latitude, point.longitude
                )
                if (dist > maxDistanceMeters) return@mapNotNull null

                // Se o ponto tiver direção e o veículo tiver direção, verifica compatibilidade angular
                if (point.bearing != null && vehicleBearing != null) {
                    val angleDiff = RadarOverlayService.calculateAngularDifference(
                        vehicleBearing.toDouble(), point.bearing
                    )
                    if (angleDiff > RadarOverlayService.BEARING_TOLERANCE_DEGREES) {
                        return@mapNotNull null
                    }
                }
                Pair(point, dist)
            }.minByOrNull { it.second }?.first
        }
    }

    fun addPoint(point: RoadSpeedPoint) {
        synchronized(roadPoints) {
            roadPoints.add(point)
        }
    }

    fun clear() {
        synchronized(roadPoints) {
            roadPoints.clear()
        }
    }

    val pointCount: Int
        get() = synchronized(roadPoints) { roadPoints.size }
}
