package com.jozito.radarlite.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerenciador de preferências do usuário persistidas com SharedPreferences.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Se true: o alerta sonoro só dispara se o veículo estiver ESTRITAMENTE acima da velocidade do radar (> radar.speedLimit).
     * Se false: o alerta sonoro dispara quando o veículo atingir >= 90% da velocidade do radar.
     */
    var warnOnlyOverSpeed: Boolean
        get() = prefs.getBoolean(KEY_WARN_ONLY_OVER_SPEED, false)
        set(value) = prefs.edit().putBoolean(KEY_WARN_ONLY_OVER_SPEED, value).apply()

    /**
     * Define se o alerta sonoro (bipe via SoundPool) está ativado.
     * Valor padrão: true.
     * Quando false, a janela flutuante visual continua sendo exibida normalmente.
     */
    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_SOUND_ENABLED, value).apply()

    /**
     * Define se o Modo Noturno (fundo escuro de alto contraste, visão direta) está ativado.
     * Quando true, aplica fundo preto (#000000) e alto contraste para não ofuscar à noite,
     * mantendo a visualização normal direta (sem espelhar a tela).
     */
    var isNightModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_NIGHT_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_NIGHT_MODE_ENABLED, value).apply()

    /**
     * Define se o Modo HUD (Head-Up Display: espelhamento para para-brisa) está ativado.
     * Quando true, espelha horizontalmente o card para reflexão perfeita no vidro do para-brisa.
     * Deve ser usado apenas com o celular deitado sobre o painel.
     */
    var isHudModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_HUD_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_HUD_MODE_ENABLED, value).apply()

    /**
     * Define se a inicialização e encerramento automáticos por Bluetooth veicular estão ativados.
     * Valor padrão: false (deve ser habilitado pelo usuário após selecionar o dispositivo do carro).
     */
    var isBluetoothAutostartEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_BT_AUTOSTART_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_BT_AUTOSTART_ENABLED, value).apply()

    /**
     * Endereço MAC do dispositivo Bluetooth veicular configurado (ex: "AA:BB:CC:DD:EE:FF").
     * Se null ou vazio, qualquer conexão Bluetooth é aceita como gatilho (modo genérico).
     */
    var selectedBluetoothDeviceAddress: String?
        get() = prefs.getString(KEY_SELECTED_BT_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_BT_DEVICE_ADDRESS, value).apply()

    /**
     * Nome amigável do dispositivo Bluetooth veicular configurado (ex: "Multimídia Pioneer", "SYNC").
     */
    var selectedBluetoothDeviceName: String?
        get() = prefs.getString(KEY_SELECTED_BT_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_BT_DEVICE_NAME, value).apply()

    /**
     * Timestamp (ms) da última sincronização bem-sucedida via GitHub.
     */
    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, value).apply()

    /**
     * Quantidade de radares salvos na última sincronização bem-sucedida.
     */
    var lastSyncCount: Int
        get() = prefs.getInt(KEY_LAST_SYNC_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SYNC_COUNT, value).apply()

    /**
     * Distância de alerta para vias de baixa velocidade (radares até 40 km/h).
     * Valor padrão: 180 metros.
     */
    var distLowSpeed: Int
        get() = prefs.getInt(KEY_DIST_LOW_SPEED, DEFAULT_DIST_LOW_SPEED)
        set(value) = prefs.edit().putInt(KEY_DIST_LOW_SPEED, value).apply()

    /**
     * Distância de alerta para vias urbanas (radares de 41 a 60 km/h).
     * Valor padrão: 220 metros.
     */
    var distCity: Int
        get() = prefs.getInt(KEY_DIST_CITY, DEFAULT_DIST_CITY)
        set(value) = prefs.edit().putInt(KEY_DIST_CITY, value).apply()

    /**
     * Distância de alerta para vias expressas (radares de 61 a 90 km/h).
     * Valor padrão: 350 metros.
     */
    var distExpress: Int
        get() = prefs.getInt(KEY_DIST_EXPRESS, DEFAULT_DIST_EXPRESS)
        set(value) = prefs.edit().putInt(KEY_DIST_EXPRESS, value).apply()

    /**
     * Distância de alerta para rodovias (radares acima de 90 km/h).
     * Valor padrão: 500 metros.
     */
    var distHighway: Int
        get() = prefs.getInt(KEY_DIST_HIGHWAY, DEFAULT_DIST_HIGHWAY)
        set(value) = prefs.edit().putInt(KEY_DIST_HIGHWAY, value).apply()

    /**
     * Define se radares da fonte OpenStreetMap (OSM) devem ser bloqueados globalmente.
     * Valor padrão: false (mantém radares OSM ativos para curadoria manual transparente do usuário).
     */
    var blockOsmRadars: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_OSM_RADARS, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_OSM_RADARS, value).apply()

    /**
     * Conjunto de IDs de radares banidos localmente pelo usuário (Fase 9).
     */
    val blacklistedRadarIds: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLISTED_RADAR_IDS, emptySet()) ?: emptySet()

    /**
     * Retorna o conjunto de IDs banidos convertidos para Long (Fase 9.1).
     */
    fun getBlacklistedRadarLongIds(): Set<Long> {
        return blacklistedRadarIds.mapNotNull { it.toLongOrNull() }.toSet()
    }

    /**
     * Adiciona o ID de um radar à Blacklist local (Fase 9).
     */
    fun banRadar(radarId: Long) {
        banRadar(radarId.toString())
    }

    fun banRadar(radarId: String) {
        val current = blacklistedRadarIds.toMutableSet()
        current.add(radarId)
        prefs.edit().putStringSet(KEY_BLACKLISTED_RADAR_IDS, current).apply()
    }

    /**
     * Verifica se o radar está banido na Blacklist local (Fase 9).
     */
    fun isRadarBanned(radarId: Long): Boolean {
        return isRadarBanned(radarId.toString())
    }

    fun isRadarBanned(radarId: String): Boolean {
        return blacklistedRadarIds.contains(radarId)
    }

    // Métodos utilitários e aliases para compatibilidade
    fun addBlacklistedRadarId(radarId: Long) = banRadar(radarId)
    fun addBlacklistedRadarId(radarId: String) = banRadar(radarId)
    fun isRadarBlacklisted(radarId: Long): Boolean = isRadarBanned(radarId)
    fun isRadarBlacklisted(radarId: String): Boolean = isRadarBanned(radarId)
    fun unbanRadar(radarId: Long) = removeBlacklistedRadarId(radarId)
    fun unbanRadar(radarId: String) = removeBlacklistedRadarId(radarId)

    /**
     * Remove um radar da Blacklist local.
     */
    fun removeBlacklistedRadarId(radarId: Long) {
        removeBlacklistedRadarId(radarId.toString())
    }

    fun removeBlacklistedRadarId(radarId: String) {
        val current = blacklistedRadarIds.toMutableSet()
        current.remove(radarId)
        prefs.edit().putStringSet(KEY_BLACKLISTED_RADAR_IDS, current).apply()
    }

    /**
     * Limpa toda a Blacklist de radares banidos.
     */
    fun clearBlacklistedRadars() {
        prefs.edit().remove(KEY_BLACKLISTED_RADAR_IDS).apply()
    }

    companion object {
        private const val PREFS_NAME = "radar_lite_prefs"
        const val KEY_WARN_ONLY_OVER_SPEED = "warn_only_over_speed"
        const val KEY_IS_SOUND_ENABLED = "is_sound_enabled"
        const val KEY_IS_NIGHT_MODE_ENABLED = "is_night_mode_enabled"
        const val KEY_IS_HUD_MODE_ENABLED = "is_hud_mode_enabled"
        const val KEY_IS_BT_AUTOSTART_ENABLED = "is_bt_autostart_enabled"
        const val KEY_SELECTED_BT_DEVICE_ADDRESS = "selected_bt_device_address"
        const val KEY_SELECTED_BT_DEVICE_NAME = "selected_bt_device_name"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val KEY_LAST_SYNC_COUNT = "last_sync_count"

        // Fase 8: Distâncias customizáveis por limite de velocidade da via
        const val KEY_DIST_LOW_SPEED = "dist_low_speed"
        const val KEY_DIST_CITY = "dist_city"
        const val KEY_DIST_EXPRESS = "dist_express"
        const val KEY_DIST_HIGHWAY = "dist_highway"

        const val DEFAULT_DIST_LOW_SPEED = 180
        const val DEFAULT_DIST_CITY = 220
        const val DEFAULT_DIST_EXPRESS = 350
        const val DEFAULT_DIST_HIGHWAY = 500

        // Fase 9: Filtro Anti-Falsos Positivos (OSM e Blacklist)
        const val KEY_BLOCK_OSM_RADARS = "block_osm_radars"
        const val KEY_BLACKLISTED_RADAR_IDS = "blacklisted_radar_ids"

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
