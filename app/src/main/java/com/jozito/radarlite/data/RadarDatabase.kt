package com.jozito.radarlite.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@Database(
    entities = [RadarEntity::class, RoadSpeedEntity::class],
    version = 4,
    exportSchema = false
)
abstract class RadarDatabase : RoomDatabase() {

    abstract fun radarDao(): RadarDao
    abstract fun roadSpeedDao(): RoadSpeedDao

    companion object {
        private const val TAG = "RadarDatabase"

        val SUPPORTED_STATES = listOf("PR", "SC", "SP", "MS")

        @Volatile
        private var INSTANCE: RadarDatabase? = null

        private const val DATABASE_NAME = "radars.db"

        /**
         * Popula a base de dados com os CSVs de fábrica em assets/ (PR, SC, SP e MS).
         * Pode ser chamada sincronicamente no Dispatchers.IO ou via coroutine.
         */
        suspend fun populateFactoryRadarsSync(context: Context, radarDao: RadarDao) {
            try {
                val assetFiles = listOf("PR.csv", "SC.csv", "SP.csv", "MS.csv")
                val factoryRadars = mutableListOf<RadarEntity>()

                for (fileName in assetFiles) {
                    try {
                        context.assets.open(fileName).use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                                // Pula a primeira linha (cabeçalho)
                                reader.readLine()
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val currentLine = line ?: continue
                                    RadarSyncManager.parseCsvLine(currentLine)?.let { radar ->
                                        factoryRadars.add(radar)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao carregar asset $fileName", e)
                    }
                }

                if (factoryRadars.isNotEmpty()) {
                    radarDao.insertAll(factoryRadars)
                    Log.i(TAG, "Base de fábrica gravada com sucesso: ${factoryRadars.size} radares.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao popular base de fábrica dos assets", e)
            }
        }

        /**
         * Popula a tabela de velocidades das vias com os CSVs de fábrica em assets/ para os 4 estados (PR, SC, SP, MS).
         * Se os pontos já estiverem no banco, retorna imediatamente para economizar CPU e bateria.
         * Realiza a inserção em lotes de 5.000 pontos em streaming para máxima eficiência de memória RAM.
         */
        suspend fun populateFactoryRoadSpeedsSync(context: Context, roadSpeedDao: RoadSpeedDao) {
            try {
                val totalInDb = roadSpeedDao.getRoadSpeedCount()
                if (totalInDb > 0) {
                    Log.d(TAG, "Tabela de velocidades das vias já populada ($totalInDb pontos). Pulando carga de fábrica.")
                    return
                }

                val speedAssets = listOf("PR_speeds.csv", "SC_speeds.csv", "SP_speeds.csv", "MS_speeds.csv")
                val parser = RoadSpeedManager()
                var totalInserted = 0

                for (fileName in speedAssets) {
                    val state = fileName.substringBefore("_")
                    try {
                        context.assets.open(fileName).use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                                var isFirst = true
                                val batch = ArrayList<RoadSpeedEntity>(5000)
                                for (line in lines) {
                                    if (isFirst) {
                                        isFirst = false
                                        if (line.startsWith("lat", ignoreCase = true)) continue
                                    }
                                    parser.parseCsvLine(line)?.let { point ->
                                        batch.add(point.toEntity(state = state))
                                        if (batch.size >= 5000) {
                                            roadSpeedDao.insertAll(batch)
                                            totalInserted += batch.size
                                            batch.clear()
                                        }
                                    }
                                }
                                if (batch.isNotEmpty()) {
                                    roadSpeedDao.insertAll(batch)
                                    totalInserted += batch.size
                                    batch.clear()
                                }
                            }
                        }
                    } catch (_: java.io.FileNotFoundException) {
                        // Asset opcional de velocidades ainda não empacotado para este estado
                    } catch (e: Exception) {
                        Log.w(TAG, "Aviso ao carregar asset de velocidades $fileName", e)
                    }
                }

                if (totalInserted > 0) {
                    Log.i(TAG, "Base de fábrica de velocidades das vias gravada com sucesso: $totalInserted pontos.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao popular base de velocidades das vias", e)
            }
        }

        fun getDatabase(context: Context): RadarDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    RadarDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getDatabase(appContext)
                                populateFactoryRadarsSync(appContext, database.radarDao())
                                populateFactoryRoadSpeedsSync(appContext, database.roadSpeedDao())
                            }
                        }

                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getDatabase(appContext)
                                populateFactoryRadarsSync(appContext, database.radarDao())
                                populateFactoryRoadSpeedsSync(appContext, database.roadSpeedDao())
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): RadarDatabase = getDatabase(context)
    }
}
