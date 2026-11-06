package com.jozito.radarlite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.abs
import kotlin.math.roundToInt

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
     * Preferência unificada de tema para toda a aplicação (Auto / Dia / Noite).
     * Controla tanto a tela principal/menus quanto os cards flutuantes.
     */
    var themePreference: String
        get() {
            val pref = prefs.getString(KEY_THEME_PREFERENCE, null)
            if (pref != null) return pref
            return if (prefs.getBoolean(KEY_IS_NIGHT_MODE_ENABLED, false)) THEME_NIGHT else THEME_AUTO
        }
        set(value) {
            prefs.edit()
                .putString(KEY_THEME_PREFERENCE, value)
                .putBoolean(KEY_IS_NIGHT_MODE_ENABLED, value == THEME_NIGHT)
                .apply()
            applyAppTheme(value)
        }

    /**
     * Aplica dinamicamente o tema no AppCompatDelegate para que todo o app respeite a escolha.
     */
    fun applyAppTheme(theme: String = themePreference) {
        val mode = when (theme.lowercase()) {
            THEME_DAY -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Define se o Modo Noturno (fundo escuro de alto contraste, visão direta) está ativado.
     * Mantido para total compatibilidade retroativa com código existente.
     */
    var isNightModeEnabled: Boolean
        get() = themePreference == THEME_NIGHT
        set(value) {
            themePreference = if (value) THEME_NIGHT else THEME_DAY
        }

    /**
     * Define se o Modo HUD (Head-Up Display: espelhamento para para-brisa) está ativado.
     * Quando true, espelha horizontalmente o card para reflexão perfeita no vidro do para-brisa.
     * Deve ser usado apenas com o celular deitado sobre o painel.
     */
    var isHudModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_HUD_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_HUD_MODE_ENABLED, value).apply()

    /**
     * Define se o alerta flutuante deve ser exibido em card grande centralizado na tela.
     * Quando true, posiciona o card no centro da tela com dimensões ampliadas.
     * Ao clicar no card, ele é ocultado temporariamente naquele instante (sem adicionar à blacklist).
     */
    var useCenterBigCard: Boolean
        get() = prefs.getBoolean(KEY_USE_CENTER_BIG_CARD, DEFAULT_USE_CENTER_BIG_CARD)
        set(value) = prefs.edit().putBoolean(KEY_USE_CENTER_BIG_CARD, value).apply()

    /**
     * Define se a inicialização automática do RadarOverlayService está ativada (Boot, Ignição/Screen On ou Bluetooth).
     * Mapeia para a chave "is_autostart_enabled" com sincronização com a chave histórica de bluetooth.
     */
    var isAutostartEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTOSTART_ENABLED, prefs.getBoolean(KEY_IS_BT_AUTOSTART_ENABLED, false))
        set(value) {
            prefs.edit()
                .putBoolean(KEY_IS_AUTOSTART_ENABLED, value)
                .putBoolean(KEY_IS_BT_AUTOSTART_ENABLED, value)
                .apply()
        }

    /**
     * Define se a inicialização e encerramento automáticos veiculares estão ativados.
     */
    var isBluetoothAutostartEnabled: Boolean
        get() = isAutostartEnabled
        set(value) {
            isAutostartEnabled = value
        }

    /**
     * Fase 11: Estado de "Intenção do Usuário".
     * Se true: o usuário desligou manualmente o serviço (via botão interno ou comando remoto do CoolantHUD).
     * O auto-start não deve religar o serviço enquanto essa chave for true.
     * Se false: o serviço pode religar normalmente ao acordar do sleep, ligar a ignição, conectar energia ou bluetooth.
     * Valor padrão: false.
     */
    var isServiceIntentionallyStopped: Boolean
        get() = prefs.getBoolean(KEY_IS_SERVICE_INTENTIONALLY_STOPPED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_SERVICE_INTENTIONALLY_STOPPED, value).commit()
        }

    /**
     * Define de forma estritamente síncrona (commit) a intenção de parada do usuário
     * para evitar condições de corrida com pulsos de Watchdog ou inicialização paralela.
     */
    fun setServiceIntentionallyStoppedSync(value: Boolean): Boolean {
        return prefs.edit().putBoolean(KEY_IS_SERVICE_INTENTIONALLY_STOPPED, value).commit()
    }

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
     * Tempo base de aviso prévio (em segundos) antes de alcançar o radar (Fase 11 - TTC).
     * Valor padrão: 15 segundos.
     */
    var baseAlertTime: Int
        get() = prefs.getInt(KEY_BASE_ALERT_TIME, DEFAULT_BASE_ALERT_TIME)
        set(value) = prefs.edit().putInt(KEY_BASE_ALERT_TIME, value).apply()

    /**
     * Tempo para disparo de alerta agressivo (em segundos) em caso de excesso de velocidade (Fase 11 - TTC).
     * Valor padrão: 7 segundos.
     */
    var aggressiveAlertTime: Int
        get() = prefs.getInt(KEY_AGGRESSIVE_ALERT_TIME, DEFAULT_AGGRESSIVE_ALERT_TIME)
        set(value) = prefs.edit().putInt(KEY_AGGRESSIVE_ALERT_TIME, value).apply()

    /**
     * Distância mínima de piso para alerta (em metros).
     * Garante que em vias urbanas de baixa velocidade (ex: radares de 40 km/h) ou no trânsito lento,
     * o radar apareça com pelo menos esta distância de antecedência (evitando que surja a 50m-80m).
     * Valor padrão: 200 metros.
     */
    var minAlertDistance: Int
        get() = prefs.getInt(KEY_MIN_ALERT_DISTANCE, DEFAULT_MIN_ALERT_DISTANCE)
        set(value) = prefs.edit().putInt(KEY_MIN_ALERT_DISTANCE, value).apply()

    /**
     * Distância de alerta para vias de baixa velocidade (radares até 40 km/h).
     * @deprecated Substituído pela fórmula dinâmica TTC (base_alert_time / aggressive_alert_time).
     */
    @Deprecated("Substituído pela fórmula dinâmica TTC")
    var distLowSpeed: Int
        get() = prefs.getInt(KEY_DIST_LOW_SPEED, DEFAULT_DIST_LOW_SPEED)
        set(value) = prefs.edit().putInt(KEY_DIST_LOW_SPEED, value).apply()

    /**
     * Distância de alerta para vias urbanas (radares de 41 a 60 km/h).
     * @deprecated Substituído pela fórmula dinâmica TTC (base_alert_time / aggressive_alert_time).
     */
    @Deprecated("Substituído pela fórmula dinâmica TTC")
    var distCity: Int
        get() = prefs.getInt(KEY_DIST_CITY, DEFAULT_DIST_CITY)
        set(value) = prefs.edit().putInt(KEY_DIST_CITY, value).apply()

    /**
     * Distância de alerta para vias expressas (radares de 61 a 90 km/h).
     * @deprecated Substituído pela fórmula dinâmica TTC (base_alert_time / aggressive_alert_time).
     */
    @Deprecated("Substituído pela fórmula dinâmica TTC")
    var distExpress: Int
        get() = prefs.getInt(KEY_DIST_EXPRESS, DEFAULT_DIST_EXPRESS)
        set(value) = prefs.edit().putInt(KEY_DIST_EXPRESS, value).apply()

    /**
     * Distância de alerta para rodovias (radares acima de 90 km/h).
     * @deprecated Substituído pela fórmula dinâmica TTC (base_alert_time / aggressive_alert_time).
     */
    @Deprecated("Substituído pela fórmula dinâmica TTC")
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
     * Conjunto de abordagens de radares banidos localmente pelo usuário com vetor direcional (Fase 9.2).
     * Formato de cada item: "${radarId}_${bearing}" (Ex: "4092_185").
     */
    val bannedApproaches: Set<String>
        get() = prefs.getStringSet(KEY_BANNED_APPROACHES, emptySet()) ?: emptySet()

    /**
     * Mantém compatibilidade com contagens e interfaces que consultam o conjunto da blacklist.
     */
    val blacklistedRadarIds: Set<String>
        get() = bannedApproaches

    /**
     * Retorna o conjunto de IDs banidos convertidos para Long (Fase 9.1 / 9.2).
     */
    fun getBlacklistedRadarLongIds(): Set<Long> {
        return bannedApproaches.mapNotNull { entry ->
            val idPart = if (entry.contains("_")) entry.substringBefore('_') else entry
            idPart.toLongOrNull()
        }.toSet()
    }

    /**
     * Retorna a lista de azimutes/direções banidos para um dado radarId.
     */
    fun getBannedBearingsForRadar(radarId: Long): List<Double> {
        val prefix = "${radarId}_"
        return bannedApproaches
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toDoubleOrNull() }
    }

    /**
     * Bane um radar associado ao vetor direcional de aproximação do veículo (Fase 9.2).
     * Formato: "${radarId}_${bearing}" (Ex: "4092_185").
     */
    fun banApproach(radarId: Long, bearing: Double) {
        val normBearing = ((bearing.roundToInt() % 360) + 360) % 360
        val entry = "${radarId}_${normBearing}"
        val current = bannedApproaches.toMutableSet()
        current.add(entry)
        prefs.edit().putStringSet(KEY_BANNED_APPROACHES, current).apply()
    }

    fun banApproach(radarId: Long, bearing: Float) {
        banApproach(radarId, bearing.toDouble())
    }

    /**
     * Bane um radar globalmente em todas as direções (Fase 9.3: Long Press).
     * Formato da chave: "${radarId}_GLOBAL".
     */
    fun banRadarGlobally(radarId: Long) {
        val entry = "${radarId}_GLOBAL"
        val current = bannedApproaches.toMutableSet()
        current.add(entry)
        prefs.edit().putStringSet(KEY_BANNED_APPROACHES, current).apply()
    }

    /**
     * Verifica se o radar está banido em TODAS as direções (marreta global - Fase 9.3).
     */
    fun isRadarGloballyBanned(radarId: Long): Boolean {
        val idStr = radarId.toString()
        val globalKey = "${radarId}_GLOBAL"
        return bannedApproaches.contains(globalKey) || bannedApproaches.contains(idStr)
    }

    fun banRadar(radarId: Long, bearing: Float = 0f) {
        banApproach(radarId, bearing)
    }

    fun banRadar(radarId: String) {
        val longId = radarId.toLongOrNull()
        if (longId != null) {
            banApproach(longId, 0.0)
        } else {
            val current = bannedApproaches.toMutableSet()
            current.add(radarId)
            prefs.edit().putStringSet(KEY_BANNED_APPROACHES, current).apply()
        }
    }

    /**
     * Verifica se o radar está banido para o vetor direcional informado (Fase 9.2 e 9.3).
     * Prioridade máxima: se o radar foi banido globalmente ("${radarId}_GLOBAL"), retorna true imediatamente.
     * Cone de Bloqueio padrão: 60 graus.
     */
    fun isApproachBanned(radarId: Long, currentBearing: Double, toleranceDegrees: Double = 60.0): Boolean {
        val idStr = radarId.toString()
        val globalKey = "${radarId}_GLOBAL"
        if (bannedApproaches.contains(globalKey) || bannedApproaches.contains(idStr)) {
            // Marreta global: radar banido em todas as direções ou ID legado
            return true
        }

        val prefix = "${radarId}_"
        val matchingEntries = bannedApproaches.filter { it.startsWith(prefix) && it != globalKey }
        if (matchingEntries.isEmpty()) return false

        for (entry in matchingEntries) {
            val bannedBearing = entry.removePrefix(prefix).toDoubleOrNull()
            if (bannedBearing != null) {
                var angleDiff = abs(currentBearing - bannedBearing) % 360.0
                if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff
                if (angleDiff <= toleranceDegrees) {
                    return true
                }
            }
        }
        return false
    }

    fun isApproachBanned(radarId: Long, currentBearing: Float, toleranceDegrees: Double = 60.0): Boolean {
        return isApproachBanned(radarId, currentBearing.toDouble(), toleranceDegrees)
    }

    fun isRadarBanned(radarId: Long, currentBearing: Float? = null): Boolean {
        if (currentBearing != null) {
            return isApproachBanned(radarId, currentBearing)
        }
        val idStr = radarId.toString()
        val prefix = "${radarId}_"
        return bannedApproaches.any { it == idStr || it.startsWith(prefix) }
    }

    fun isRadarBanned(radarId: String): Boolean {
        val longId = radarId.toLongOrNull()
        return if (longId != null) isRadarBanned(longId) else bannedApproaches.contains(radarId)
    }

    // Métodos utilitários e aliases para compatibilidade
    fun addBlacklistedRadarId(radarId: Long) = banRadar(radarId)
    fun addBlacklistedRadarId(radarId: String) = banRadar(radarId)
    fun isRadarBlacklisted(radarId: Long): Boolean = isRadarBanned(radarId)
    fun isRadarBlacklisted(radarId: String): Boolean = isRadarBanned(radarId)
    fun unbanRadar(radarId: Long) = removeBlacklistedRadarId(radarId)
    fun unbanRadar(radarId: String) = removeBlacklistedRadarId(radarId)

    /**
     * Remove um radar da Blacklist local (remove todos os vetores associados ao ID).
     */
    fun removeBlacklistedRadarId(radarId: Long) {
        val idStr = radarId.toString()
        val prefix = "${radarId}_"
        val current = bannedApproaches.toMutableSet()
        current.removeAll { it == idStr || it.startsWith(prefix) }
        prefs.edit().putStringSet(KEY_BANNED_APPROACHES, current).apply()
    }

    fun removeBlacklistedRadarId(radarId: String) {
        val longId = radarId.toLongOrNull()
        if (longId != null) {
            removeBlacklistedRadarId(longId)
        } else {
            val current = bannedApproaches.toMutableSet()
            current.remove(radarId)
            prefs.edit().putStringSet(KEY_BANNED_APPROACHES, current).apply()
        }
    }

    /**
     * Limpa toda a Blacklist de radares banidos.
     */
    fun clearBlacklistedRadars() {
        prefs.edit()
            .remove(KEY_BANNED_APPROACHES)
            .remove(KEY_BLACKLISTED_RADAR_IDS)
            .apply()
    }

    /**
     * Conjunto de IDs de radares marcados para revisão / correção de base de dados pelo usuário.
     */
    val flaggedForReview: Set<String>
        get() = prefs.getStringSet(KEY_FLAGGED_FOR_REVIEW, emptySet()) ?: emptySet()

    fun flagRadarForReview(radarId: Long): Boolean {
        val current = flaggedForReview.toMutableSet()
        val added = current.add(radarId.toString())
        if (added) {
            prefs.edit().putStringSet(KEY_FLAGGED_FOR_REVIEW, current).apply()
        }
        return added
    }

    fun unflagRadarForReview(radarId: Long) {
        val current = flaggedForReview.toMutableSet()
        if (current.remove(radarId.toString())) {
            prefs.edit().putStringSet(KEY_FLAGGED_FOR_REVIEW, current).apply()
        }
    }

    fun clearFlaggedRadars() {
        prefs.edit().remove(KEY_FLAGGED_FOR_REVIEW).apply()
    }

    companion object {
        private const val PREFS_NAME = "radar_lite_prefs"
        const val KEY_THEME_PREFERENCE = "theme_preference"
        const val THEME_AUTO = "auto"
        const val THEME_DAY = "day"
        const val THEME_NIGHT = "night"

        const val KEY_FLAGGED_FOR_REVIEW = "flagged_for_review"

        const val KEY_WARN_ONLY_OVER_SPEED = "warn_only_over_speed"
        const val KEY_IS_SOUND_ENABLED = "is_sound_enabled"
        const val KEY_IS_NIGHT_MODE_ENABLED = "is_night_mode_enabled"
        const val KEY_IS_HUD_MODE_ENABLED = "is_hud_mode_enabled"
        const val KEY_USE_CENTER_BIG_CARD = "use_center_big_card"
        const val DEFAULT_USE_CENTER_BIG_CARD = false
        const val KEY_IS_AUTOSTART_ENABLED = "is_autostart_enabled"
        const val KEY_IS_BT_AUTOSTART_ENABLED = "is_bt_autostart_enabled"
        const val KEY_IS_SERVICE_INTENTIONALLY_STOPPED = "is_service_intentionally_stopped"
        const val KEY_SELECTED_BT_DEVICE_ADDRESS = "selected_bt_device_address"
        const val KEY_SELECTED_BT_DEVICE_NAME = "selected_bt_device_name"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val KEY_LAST_SYNC_COUNT = "last_sync_count"

        // Fase 11: Fórmula de Alerta por Tempo de Colisão (TTC) Variável e Piso de Segurança
        const val KEY_BASE_ALERT_TIME = "base_alert_time"
        const val KEY_AGGRESSIVE_ALERT_TIME = "aggressive_alert_time"
        const val KEY_MIN_ALERT_DISTANCE = "min_alert_distance"
        const val DEFAULT_BASE_ALERT_TIME = 15 // segundos
        const val DEFAULT_AGGRESSIVE_ALERT_TIME = 7 // segundos
        const val DEFAULT_MIN_ALERT_DISTANCE = 200 // metros (piso de segurança urbana)

        // Fase 8: Distâncias customizáveis (legado mantido para migração limpa)
        const val KEY_DIST_LOW_SPEED = "dist_low_speed"
        const val KEY_DIST_CITY = "dist_city"
        const val KEY_DIST_EXPRESS = "dist_express"
        const val KEY_DIST_HIGHWAY = "dist_highway"

        const val DEFAULT_DIST_LOW_SPEED = 180
        const val DEFAULT_DIST_CITY = 220
        const val DEFAULT_DIST_EXPRESS = 350
        const val DEFAULT_DIST_HIGHWAY = 500

        // Fase 9 e 9.2: Filtro Anti-Falsos Positivos e Blacklist Vetorial Direcional
        const val KEY_BLOCK_OSM_RADARS = "block_osm_radars"
        const val KEY_BANNED_APPROACHES = "banned_approaches"
        const val KEY_BLACKLISTED_RADAR_IDS = "blacklisted_radar_ids" // mantido para migração limpa

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
