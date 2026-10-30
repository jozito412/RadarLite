package com.jozito.radarlite.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlin.math.abs
import kotlin.math.cos

@Dao
interface RadarDao {

    /**
     * Query rápida com BETWEEN (Bounding Box) recebendo latitude, longitude e deltas.
     */
    @Query("""
        SELECT * FROM radars 
        WHERE latitude BETWEEN (:latitude - :latDelta) AND (:latitude + :latDelta)
          AND longitude BETWEEN (:longitude - :lonDelta) AND (:longitude + :lonDelta)
    """)
    suspend fun getRadarsNearby(
        latitude: Double,
        longitude: Double,
        latDelta: Double,
        lonDelta: Double
    ): List<RadarEntity>

    /**
     * Query direta com Bounding Box (coordenadas mínimas e máximas).
     */
    @Query("""
        SELECT * FROM radars 
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
    """)
    suspend fun getRadarsInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RadarEntity>

    /**
     * Método auxiliar que recebe a posição atual (lat, lon) e um raio em metros (ex: 3000 metros = 3km),
     * calcula a caixa delimitadora (bounding box) e executa a query SQL com BETWEEN.
     */
    suspend fun getRadarsWithinRadius(
        latitude: Double,
        longitude: Double,
        radiusInMeters: Double = 3000.0
    ): List<RadarEntity> {
        val latDelta = radiusInMeters / 111320.0
        val cosLat = cos(Math.toRadians(latitude))
        val lonDelta = if (abs(cosLat) > 1e-6) {
            radiusInMeters / (111320.0 * abs(cosLat))
        } else {
            latDelta
        }

        return getRadarsInBoundingBox(
            minLat = latitude - latDelta,
            maxLat = latitude + latDelta,
            minLon = longitude - lonDelta,
            maxLon = longitude + lonDelta
        )
    }

    @Query("SELECT * FROM radars WHERE id = :id LIMIT 1")
    suspend fun getRadarById(id: Long): RadarEntity?

    /**
     * Recupera dados completos dos radares especificados pelos seus IDs (Fase 9.1).
     */
    @Query("SELECT * FROM radars WHERE id IN (:radarIds)")
    suspend fun getRadarsByIds(radarIds: Set<Long>): List<RadarEntity>

    @Query("SELECT * FROM radars")
    suspend fun getAllRadars(): List<RadarEntity>

    @Query("SELECT COUNT(*) FROM radars")
    suspend fun getRadarCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(radars: List<RadarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(radar: RadarEntity)

    @Query("DELETE FROM radars")
    suspend fun deleteAll()

    /**
     * Transação Atômica (Safe Swap): apaga a base anterior e insere a nova base.
     * Caso ocorra qualquer falha durante a inserção, a transação sofre rollback
     * e os dados anteriores são preservados intactos.
     */
    @Transaction
    suspend fun replaceAllRadars(newRadars: List<RadarEntity>) {
        deleteAll()
        insertAll(newRadars)
    }
}
