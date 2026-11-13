package com.jozito.radarlite.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlin.math.abs
import kotlin.math.cos

@Dao
interface RoadSpeedDao {

    @Query("""
        SELECT * FROM road_speeds 
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
    """)
    suspend fun getRoadSpeedsInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RoadSpeedEntity>

    /**
     * Busca pontos de velocidade em um raio (metros) em torno da coordenada.
     */
    suspend fun getRoadSpeedsWithinRadius(
        latitude: Double,
        longitude: Double,
        radiusInMeters: Double = 500.0
    ): List<RoadSpeedEntity> {
        val latDelta = radiusInMeters / 111320.0
        val cosLat = cos(Math.toRadians(latitude))
        val lonDelta = if (abs(cosLat) > 1e-6) {
            radiusInMeters / (111320.0 * abs(cosLat))
        } else {
            latDelta
        }

        return getRoadSpeedsInBoundingBox(
            minLat = latitude - latDelta,
            maxLat = latitude + latDelta,
            minLon = longitude - lonDelta,
            maxLon = longitude + lonDelta
        )
    }

    @Query("SELECT * FROM road_speeds WHERE state = :state")
    suspend fun getRoadSpeedsByState(state: String): List<RoadSpeedEntity>

    @Query("SELECT COUNT(*) FROM road_speeds")
    suspend fun getRoadSpeedCount(): Int

    @Query("SELECT COUNT(*) FROM road_speeds WHERE state = :state")
    suspend fun getRoadSpeedCountByState(state: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<RoadSpeedEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: RoadSpeedEntity)

    @Query("DELETE FROM road_speeds WHERE state = :state")
    suspend fun deleteByState(state: String)

    @Query("DELETE FROM road_speeds")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceStateRoadSpeeds(state: String, points: List<RoadSpeedEntity>) {
        deleteByState(state)
        insertAll(points)
    }

    @Transaction
    suspend fun replaceAllRoadSpeeds(points: List<RoadSpeedEntity>) {
        deleteAll()
        insertAll(points)
    }
}
