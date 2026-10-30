package com.jozito.radarlite.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Responsável por baixar os dados de radares diretamente do repositório Murkiriel/pardal no GitHub,
 * realizar o parsing dos arquivos CSV dos estados PR, SC, SP e MS, e persistir os dados no Room.
 */
class RadarSyncManager(
    private val context: Context,
    private val radarDao: RadarDao
) {

    companion object {
        private const val TAG = "RadarSyncManager"

        // URLs diretos (Raw) do GitHub contendo os CSVs consolidados dos 4 estados
        val STATE_CSV_URLS = listOf(
            "https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/PR/radares.csv",
            "https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/SC/radares.csv",
            "https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/SP/radares.csv",
            "https://raw.githubusercontent.com/Murkiriel/pardal/main/estados/MS/radares.csv"
        )

        /**
         * Realiza o parsing de uma linha do CSV:
         * Formato: lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg
         *
         * Regras críticas:
         * - Coluna 0: latitude
         * - Coluna 1: longitude
         * - Coluna 2: type (kind)
         * - Coluna 3: speedLimit (se vazio, assume 0)
         * - Coluna 4: source (ex: "OSM", "DNIT", "ANTT", se vazio, assume null)
         * - Coluna 8: bearing (se vazio, assume null)
         */
        fun parseCsvLine(line: String): RadarEntity? {
            if (line.isBlank()) return null
            val parts = line.split(",")
            if (parts.size < 3) return null

            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            val kind = parts.getOrNull(2)?.trim()?.ifEmpty { "RADAR" } ?: "RADAR"

            // Coluna 3: limit_kmh (se for vazia ou inválida, assume 0)
            val speedLimit = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0

            // Coluna 4: source (ex: "OSM", "DNIT", etc. - Fase 9)
            val source = parts.getOrNull(4)?.trim()?.ifEmpty { null }

            // Coluna 8: direction_deg (se for vazia, grava bearing como nulo)
            val bearing = parts.getOrNull(8)?.trim()?.toDoubleOrNull()

            return RadarEntity(
                latitude = lat,
                longitude = lng,
                speedLimit = speedLimit,
                type = kind,
                bearing = bearing,
                source = source
            )
        }
    }

    data class SyncResult(
        val isSuccess: Boolean,
        val totalRadarsSaved: Int = 0,
        val errorMessage: String? = null
    )

    /**
     * Executa o download sequencial dos CSVs dos 4 estados em memória em uma lista temporária.
     * Caso todas as requisições tenham sucesso, executa o Safe Swap chamando radarDao.replaceAllRadars(temporaryRadars).
     * Se a rede falhar ou ocorrer qualquer erro, a exceção é capturada, o swap NÃO acontece e a base
     * de dados de fábrica é mantida intacta.
     *
     * @param onProgress Callback opcional para notificar o progresso de cada estado baixado.
     */
    suspend fun syncRadars(
        onProgress: (state: String, currentTotal: Int) -> Unit = { _, _ -> }
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            // Lista temporária em memória para blindar o banco contra downloads incompletos
            val temporaryRadars = mutableListOf<RadarEntity>()

            for (url in STATE_CSV_URLS) {
                val state = url.split("/").let { it[it.size - 2] } // PR, SC, SP, MS
                Log.d(TAG, "Iniciando download do estado $state ($url)...")

                val stateRadars = downloadAndParseCsv(url)
                temporaryRadars.addAll(stateRadars)
                Log.d(TAG, "${stateRadars.size} radares carregados para $state.")
                onProgress(state, temporaryRadars.size)
            }

            if (temporaryRadars.isEmpty()) {
                throw IllegalStateException("Nenhum radar válido foi retornado dos arquivos CSV.")
            }

            // Safe Swap (Transação Atômica): limpa a base anterior e grava a nova na mesma transação
            Log.d(TAG, "Executando Safe Swap atômico com ${temporaryRadars.size} radares...")
            radarDao.replaceAllRadars(temporaryRadars)

            val countInDatabase = radarDao.getRadarCount()
            Log.i(TAG, "Sincronização concluída com sucesso! Total no banco: $countInDatabase")

            // Grava os metadados da sincronização persistidos
            val prefs = PreferencesManager.getInstance(context)
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            prefs.lastSyncCount = countInDatabase

            SyncResult(isSuccess = true, totalRadarsSaved = countInDatabase)
        } catch (e: Exception) {
            Log.e(TAG, "Falha na sincronização. O Safe Swap não ocorreu e a base existente permanece intacta.", e)
            SyncResult(
                isSuccess = false,
                errorMessage = e.localizedMessage ?: "Erro de conexão ao baixar radares."
            )
        }
    }

    /**
     * Abre a conexão HTTP com o GitHub Raw e processa o stream do CSV linha por linha.
     * Lança exceção em caso de erro HTTP para evitar salvar bases corrompidas ou incompletas.
     */
    private fun downloadAndParseCsv(urlString: String): List<RadarEntity> {
        val radars = mutableListOf<RadarEntity>()
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "RadarLite-Android")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("Falha HTTP $responseCode ao tentar obter $urlString")
            }

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                // Pular o cabeçalho (primeira linha)
                reader.readLine()

                // Processar cada linha subsequente
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    parseCsvLine(currentLine)?.let { radar ->
                        radars.add(radar)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (radars.isEmpty()) {
            throw java.io.IOException("Nenhum radar válido foi extraído de $urlString")
        }

        return radars
    }
}
