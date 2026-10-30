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
    entities = [RadarEntity::class],
    version = 3,
    exportSchema = false
)
abstract class RadarDatabase : RoomDatabase() {

    abstract fun radarDao(): RadarDao

    companion object {
        private const val TAG = "RadarDatabase"

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
                            }
                        }

                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getDatabase(appContext)
                                populateFactoryRadarsSync(appContext, database.radarDao())
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
