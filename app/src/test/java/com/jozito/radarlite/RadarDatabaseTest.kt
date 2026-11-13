package com.jozito.radarlite

import com.jozito.radarlite.data.RadarEntity
import com.jozito.radarlite.data.toPoint
import com.jozito.radarlite.data.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class RadarDatabaseTest {

    @Test
    fun testRadarEntityCreation() {
        val radar = RadarEntity(
            id = 101L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            bearing = 180.5,
            source = "ANTT"
        )

        assertEquals(101L, radar.id)
        assertEquals(-25.4284, radar.latitude, 0.0001)
        assertEquals(-49.2733, radar.longitude, 0.0001)
        assertEquals(60, radar.speedLimit)
        assertEquals("FIXED", radar.type)
        assertEquals(180.5, radar.bearing!!, 0.01)
        assertEquals("ANTT", radar.source)
    }

    @Test
    fun testRadarEntityNullableBearingAndSource() {
        val radar = RadarEntity(
            id = 102L,
            latitude = -23.5505,
            longitude = -46.6333,
            speedLimit = 80,
            type = "TRAFFIC_LIGHT"
        )

        assertEquals(102L, radar.id)
        assertNull(radar.bearing)
        assertNull(radar.source)
    }

    @Test
    fun testBoundingBoxDeltaCalculation() {
        val lat = -25.4284 // Curitiba, PR
        val lon = -49.2733
        val radiusMeters = 3000.0 // 3 km

        val latDelta = radiusMeters / 111320.0
        val cosLat = cos(Math.toRadians(lat))
        val lonDelta = radiusMeters / (111320.0 * abs(cosLat))

        val minLat = lat - latDelta
        val maxLat = lat + latDelta
        val minLon = lon - lonDelta
        val maxLon = lon + lonDelta

        // Radar 100 metros ao norte deve estar dentro do bounding box
        val radarNearbyLat = lat + (100.0 / 111320.0)
        val radarNearbyLon = lon

        assert(radarNearbyLat in minLat..maxLat)
        assert(radarNearbyLon in minLon..maxLon)

        // Radar a 50 km de distância deve estar fora
        val radarFarLat = lat + (50000.0 / 111320.0)
        assert(radarFarLat !in minLat..maxLat)
    }

    @Test
    fun testRoadSpeedEntityCreationAndStatePartition() {
        val entity = com.jozito.radarlite.data.RoadSpeedEntity(
            id = 1L,
            latitude = -26.610278,
            longitude = -51.480105,
            speedLimit = 100,
            source = "OSM:class",
            isEstimated = true,
            speedLimitLow = 80,
            bearing = 90.0,
            state = "PR"
        )

        assertEquals(1L, entity.id)
        assertEquals(-26.610278, entity.latitude, 0.00001)
        assertEquals(-51.480105, entity.longitude, 0.00001)
        assertEquals(100, entity.speedLimit)
        assertEquals("OSM:class", entity.source)
        assertEquals(true, entity.isEstimated)
        assertEquals(80, entity.speedLimitLow)
        assertEquals(90.0, entity.bearing!!, 0.01)
        assertEquals("PR", entity.state)

        // Conversão de/para RoadSpeedPoint
        val point = entity.toPoint()
        assertEquals(entity.latitude, point.latitude, 0.00001)
        assertEquals(entity.speedLimit, point.speedLimit)

        val backToEntity = point.toEntity(state = "PR")
        assertEquals(entity.state, backToEntity.state)
    }

    @Test
    fun testSupportedStatesList() {
        val states = com.jozito.radarlite.data.RadarDatabase.SUPPORTED_STATES
        assertEquals(4, states.size)
        assertEquals(listOf("PR", "SC", "SP", "MS"), states)
    }
}
