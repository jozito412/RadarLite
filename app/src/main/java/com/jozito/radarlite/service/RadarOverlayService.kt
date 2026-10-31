package com.jozito.radarlite.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.location.Location
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.North
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jozito.radarlite.R
import com.jozito.radarlite.data.PreferencesManager
import com.jozito.radarlite.data.RadarDao
import com.jozito.radarlite.data.RadarDatabase
import com.jozito.radarlite.data.RadarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radar próximo identificado com cálculo de distância, tempo de colisão (TTC) e alcance de alerta configurado.
 */
data class NearbyRadar(
    val radar: RadarEntity,
    val distanceMeters: Float,
    val alertDistanceMeters: Float = 500f,
    val timeToCollisionSec: Double = 0.0,
    val isAggressiveAlert: Boolean = false
)

/**
 * Alias de tipo para garantir compatibilidade com referências a RadarLocationService.
 */
typealias RadarLocationService = RadarOverlayService

/**
 * Foreground Service responsável por:
 * 1. Rastrear a localização a cada segundo (1000ms) usando FusedLocationProviderClient.
 * 2. Converter a velocidade de m/s para km/h.
 * 3. Detectar radares a menos de 500m na mesma direção (+/- 30 graus de tolerância).
 * 4. Tocar som de alerta via SoundPool quando a velocidade for >= 90% do limite do radar (com cooldown de 15s).
 * 5. Exibir uma janela flutuante (Overlay) sobre a tela usando WindowManager e Jetpack Compose.
 *
 * Implementa [ViewModelStoreOwner] e [SavedStateRegistryOwner] para que a árvore de UI do Jetpack Compose
 * funcione de forma autônoma fora de uma Activity.
 */
class RadarOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var radarDao: RadarDao
    private lateinit var preferencesManager: PreferencesManager

    // WindowManager e ComposeView do Overlay
    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var isOverlayAttached: Boolean = false

    // Suporte aos donos da árvore Compose
    private val serviceViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = serviceViewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // Áudio com SoundPool
    private var soundPool: SoundPool? = null
    private var beepSoundId: Int = 0
    private var isSoundLoaded: Boolean = false

    // Controle de cooldown de 15 segundos por radar (radar.id -> timestamp em milissegundos)
    private val radarAlertCooldowns = ConcurrentHashMap<Long, Long>()

    // Modo Standby em parada prolongada (velocidade zero por mais de 5 minutos contínuos)
    private var stoppedStartTime: Long? = null
    private var isStandbyMode: Boolean = false
    private val STANDBY_TIMEOUT_MS: Long = 5 * 60 * 1000L

    // Caching Espacial em RAM (Anti-Superaquecimento)
    // O DAO só deve ser acionado (buscando um raio amplo de 15 km) se cachedRadars estiver vazio
    // ou se a distância entre a posição atual e o lastQueryLocation for maior que 3 km.
    private var cachedRadars: List<RadarEntity> = emptyList()
    private var lastQueryLocation: Location? = null

    // Rastreamento inteligente de direção (Bearing) do veículo
    // Evita falsos positivos e cones invertidos quando o GPS não reporta bearing ou o carro está devagar/parado
    private var lastValidBearing: Float? = null
    private var lastLocationForBearing: Location? = null

    // Timeout de GPS (Túneis e Zonas Mortas)
    // Se a última leitura do GPS for mais antiga que 10.000ms, assume perda de sinal
    private var lastGpsUpdateTimestamp: Long = 0L
    private var gpsWatchdogJob: Job? = null

    // WakeLock para manter a CPU e GPS acordados mesmo com a tela apagada no bolso ou console do carro
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RadarLite:RadarTrackingWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12h de viagem máxima contínua como proteção
                Log.d(TAG, "WakeLock (PARTIAL_WAKE_LOCK) adquirido para blindagem de execução contínua.")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock liberado com sucesso.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    companion object {
        private const val TAG = "RadarOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "radar_tracking_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Rastreamento de Radares"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        // Ações e extras para transmissão de estado para Launchers parceiros (ex: CoolantHUD)
        const val ACTION_STATE_CHANGED = "com.jozito412.radarlite.ACTION_STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "is_running"

        /**
         * Emite um Broadcast anunciando a mudança de estado de execução do serviço.
         * Usado para sincronização de via dupla com Launchers externos (ex: CoolantHUD).
         */
        fun broadcastState(context: Context, isRunning: Boolean) {
            try {
                val intent = Intent(ACTION_STATE_CHANGED).apply {
                    putExtra(EXTRA_IS_RUNNING, isRunning)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Broadcast de estado emitido: $ACTION_STATE_CHANGED ($EXTRA_IS_RUNNING = $isRunning)")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao emitir broadcast de estado", e)
            }
        }

        // Intervalo de cooldown para não repetir o alerta sonoro para o mesmo radar (15s)
        const val COOLDOWN_DURATION_MS = 15_000L

        // Tolerância angular de direção padrão (+/- 30 graus)
        const val BEARING_TOLERANCE_DEGREES = 30.0

        // Constantes do Caching Espacial em RAM (Anti-Superaquecimento)
        const val CACHE_RADIUS_METERS = 15_000.0 // 15 km
        const val CACHE_EXPIRATION_DISTANCE_METERS = 3_000f // 3 km (margem segura de revalidação)

        // Constantes de Deduplicação em RAM (Fase 10)
        const val DEDUPLICATION_RADIUS_METERS = 35.0
        const val DEDUPLICATION_DIRECTION_CONFLICT_DEGREES = 45.0

        // Constante de Tolerância do Cone de Bloqueio da Blacklist Vetorial (Fase 9.2)
        const val BLACKLIST_CONE_TOLERANCE_DEGREES = 60.0

        // Timeout de sinal do GPS (10.000ms tolerante a desvios urbanos sem perder túneis)
        const val GPS_TIMEOUT_MS = 10_000L

        // Estado reativo compartilhado para indicar perda de sinal GPS
        private val _isGpsLost = MutableStateFlow(false)
        val isGpsLost: StateFlow<Boolean> = _isGpsLost.asStateFlow()

        // Modo Noturno (fundo preto absoluto de alto contraste, visão direta sem espelhar)
        private val _isNightMode = MutableStateFlow(false)
        val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

        fun setNightMode(enabled: Boolean) {
            _isNightMode.value = enabled
        }

        // Modo HUD (espelhamento horizontal exclusivamente para reflexão no para-brisa)
        private val _isHudMode = MutableStateFlow(false)
        val isHudMode: StateFlow<Boolean> = _isHudMode.asStateFlow()

        fun setHudMode(enabled: Boolean) {
            _isHudMode.value = enabled
        }

        /**
         * Verifica se o cache espacial em RAM deve ser atualizado via DAO.
         */
        fun shouldRefreshCache(
            currentLocation: Location,
            lastQueryLocation: Location?,
            cachedRadarsCount: Int
        ): Boolean {
            if (cachedRadarsCount == 0 || lastQueryLocation == null) return true
            return currentLocation.distanceTo(lastQueryLocation) > CACHE_EXPIRATION_DISTANCE_METERS
        }

        // Estado reativo compartilhado para a interface Compose da janela flutuante
        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

        private val _currentSpeedKmh = MutableStateFlow(0f)
        val currentSpeedKmh: StateFlow<Float> = _currentSpeedKmh.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _nearbyRadars = MutableStateFlow<List<NearbyRadar>>(emptyList())
        val nearbyRadars: StateFlow<List<NearbyRadar>> = _nearbyRadars.asStateFlow()

        private val _closestRadar = MutableStateFlow<NearbyRadar?>(null)
        val closestRadar: StateFlow<NearbyRadar?> = _closestRadar.asStateFlow()

        // Distância de alerta dinâmica para o radar ativo exposta para consumo da UI (Fase 8)
        private val _closestRadarAlertDistance = MutableStateFlow(PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat())
        val closestRadarAlertDistance: StateFlow<Float> = _closestRadarAlertDistance.asStateFlow()

        /**
         * Inicia o serviço em primeiro plano e reseta o estado de parada intencional do usuário (Fase 11).
         */
        fun start(context: Context) {
            PreferencesManager.getInstance(context).isServiceIntentionallyStopped = false
            val intent = Intent(context, RadarOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Para o serviço de rastreamento e registra o estado de parada intencional do usuário (Fase 11).
         */
        fun stop(context: Context) {
            PreferencesManager.getInstance(context).isServiceIntentionallyStopped = true
            val intent = Intent(context, RadarOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        /**
         * Compara a direção do veículo com a direção do radar.
         * A tolerância padrão é de +/- 30 graus.
         * Se radarBearing for null, considera que o radar monitora todos os sentidos.
         */
        fun isSameDirection(
            vehicleBearing: Float,
            radarBearing: Double?,
            toleranceDegrees: Double = BEARING_TOLERANCE_DEGREES
        ): Boolean {
            if (radarBearing == null) return true

            val diff = abs(vehicleBearing.toDouble() - radarBearing) % 360.0
            val angleDiff = if (diff > 180.0) 360.0 - diff else diff
            return angleDiff <= toleranceDegrees
        }

        /**
         * Calcula a menor diferença angular (0 a 180 graus) entre a direção do veículo
         * e a direção em linha reta até o radar.
         */
        fun calculateRelativeAngleDiff(vehicleBearing: Float, bearingToRadar: Float): Float {
            val normalizedBearingTo = if (bearingToRadar < 0) bearingToRadar + 360f else bearingToRadar
            val diff = abs(vehicleBearing - normalizedBearingTo) % 360f
            return if (diff > 180f) 360f - diff else diff
        }

        /**
         * Implementa o "Cone de Visão Frontal":
         * Se o veículo tiver uma direção conhecida (effectiveBearing != null):
         * - Calcula a diferença entre a direção de deslocamento e o vetor até o radar.
         * - Se angleDiff >= 85 graus, o radar já foi ultrapassado (está de costas para o carro) e é descartado.
         * Se a direção for desconhecida (carro acabou de ligar e ainda não se moveu):
         * - Aceita o radar (retorna true) para não descartar radares erroneamente por presumir Norte (0°).
         */
        fun isInFrontOfVehicle(
            vehicleLocation: Location,
            radarLocation: Location,
            effectiveBearing: Float? = null
        ): Boolean {
            val bearing = effectiveBearing ?: if (vehicleLocation.hasBearing() && vehicleLocation.bearing != 0.0f) {
                vehicleLocation.bearing
            } else {
                null
            }

            // Se o veículo ainda não possui um rumo de deslocamento conhecido, não podemos presumir 0° (Norte)!
            // Permite exibir os radares no raio até que o veículo estabeleça direção.
            if (bearing == null) {
                return true
            }

            val bearingToRadar = vehicleLocation.bearingTo(radarLocation)
            val angleDiff = calculateRelativeAngleDiff(bearing, bearingToRadar)
            return angleDiff < 85f
        }

        /**
         * Fase 11: Calcula o Tempo de Colisão (TTC) em segundos a partir da velocidade atual do carro.
         * Se o carro estiver a menos de 10 km/h, assume 10 km/h para evitar divisão por zero ou avisos infinitos.
         */
        fun calculateTimeToCollision(distanceMeters: Double, currentSpeedKmh: Float): Double {
            val effectiveSpeedKmh = if (currentSpeedKmh < 10f) 10f else currentSpeedKmh
            val speedMs = effectiveSpeedKmh / 3.6
            return distanceMeters / speedMs
        }

        /**
         * Fase 11: Curva de Tempo Variável (Dynamic Alert Limit).
         * - Se currentSpeedKmh > 90 km/h -> 15.0s (escalado por baseAlertTime)
         * - Se currentSpeedKmh > 60 km/h -> 12.0s
         * - Menos de 60 km/h (cidade) -> 9.0s
         */
        fun calculateDynamicAlertLimit(currentSpeedKmh: Float, baseAlertTime: Int = PreferencesManager.DEFAULT_BASE_ALERT_TIME): Double {
            val factor = baseAlertTime.toDouble() / 15.0
            val baseSec = if (currentSpeedKmh > 90f) 15.0 else if (currentSpeedKmh > 60f) 12.0 else 9.0
            return baseSec * factor
        }

        /**
         * Fase 11: Verifica se o veículo entrou na zona de alerta dinâmico por TTC.
         */
        fun isWithinTimeToCollisionAlert(
            distanceMeters: Double,
            currentSpeedKmh: Float,
            baseAlertTime: Int = PreferencesManager.DEFAULT_BASE_ALERT_TIME
        ): Boolean {
            val ttc = calculateTimeToCollision(distanceMeters, currentSpeedKmh)
            val limit = calculateDynamicAlertLimit(currentSpeedKmh, baseAlertTime)
            return ttc <= limit
        }

        /**
         * Fase 11: Determina se o alerta deve ser agressivo (Vermelho Piscando):
         * timeToCollision <= aggressiveAlertTime E currentSpeedKmh > radar.speedLimit.
         */
        fun isAggressiveTtcAlert(
            distanceMeters: Double,
            currentSpeedKmh: Float,
            radarSpeedLimit: Int,
            aggressiveAlertTime: Int = PreferencesManager.DEFAULT_AGGRESSIVE_ALERT_TIME
        ): Boolean {
            val ttc = calculateTimeToCollision(distanceMeters, currentSpeedKmh)
            return ttc <= aggressiveAlertTime.toDouble() && currentSpeedKmh > radarSpeedLimit
        }

        /**
         * Define o alertDistance avaliando a propriedade radar.speedLimit (Fase 8):
         * - Se <= 40 km/h -> use dist_low_speed (Padrão: 180m)
         * - Se <= 60 km/h -> use dist_city (Padrão: 220m)
         * - Se <= 90 km/h -> use dist_express (Padrão: 350m)
         * - Se > 90 km/h -> use dist_highway (Padrão: 500m)
         */
        fun getAlertDistanceForRadar(
            speedLimit: Int,
            distLowSpeed: Int = PreferencesManager.DEFAULT_DIST_LOW_SPEED,
            distCity: Int = PreferencesManager.DEFAULT_DIST_CITY,
            distExpress: Int = PreferencesManager.DEFAULT_DIST_EXPRESS,
            distHighway: Int = PreferencesManager.DEFAULT_DIST_HIGHWAY
        ): Float {
            return when {
                speedLimit <= 40 -> distLowSpeed.toFloat()
                speedLimit <= 60 -> distCity.toFloat()
                speedLimit <= 90 -> distExpress.toFloat()
                else -> distHighway.toFloat()
            }
        }

        // Limiar de velocidade e distâncias legadas mantidas para retrocompatibilidade
        const val HIGHWAY_SPEED_THRESHOLD_KMH = 80f
        const val ALERT_DISTANCE_HIGHWAY_METERS = 500f
        const val ALERT_DISTANCE_CITY_METERS = 250f

        /**
         * Calcula a distância dinâmica de alcance de alerta baseada na velocidade atual do carro (speedKmh):
         * - speedKmh >= 80 km/h (velocidade de rodovia): alertDistanceMeters = 500m
         * - speedKmh < 80 km/h (velocidade de cidade): alertDistanceMeters = 250m
         */
        fun getAlertDistanceMeters(speedKmh: Float): Float {
            return if (speedKmh >= HIGHWAY_SPEED_THRESHOLD_KMH) {
                ALERT_DISTANCE_HIGHWAY_METERS
            } else {
                ALERT_DISTANCE_CITY_METERS
            }
        }

        /**
         * Retorna o ícone e rótulo visual para cada tipo de radar cadastrado na base do Pardal:
         * - TRAFFIC_LIGHT: 🚦 Radar de Semáforo / Avanço de Sinal
         * - FIXED: 📸 Radar Fixo de Velocidade
         * - PEDESTRIAN_CROSSING: 🚶 Lombada / Faixa de Pedestres
         * - SECTION: ⏱️ Radar de Trecho (Velocidade Média)
         */
        fun getRadarTypeInfo(type: String): Pair<String, String> {
            return when (type.uppercase()) {
                "TRAFFIC_LIGHT" -> "🚦" to "Semáforo"
                "PEDESTRIAN_CROSSING" -> "🚶" to "Lombada"
                "SECTION" -> "⏱️" to "Trecho"
                else -> "📸" to "Radar Fixo"
            }
        }

        /**
         * Fase 9.2: Filtro de Blacklist Vetorial Direcional.
         *
         * Busca todos os bloqueios associados ao radarId dentro de bannedApproaches.
         * Cada bloqueio é salvo no formato "${radarId}_${bannedBearing}" (ou "${radarId}" legado).
         *
         * Se houver bloqueios registrados para aquele ID:
         * - Calcula a diferença angular entre o vehicleBearing (direção atual do carro) e o bannedBearing gravado:
         *   var angleDiff = abs(currentBearing - bannedBearing) % 360.0
         *   if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff
         *
         * Regra do Cone de Bloqueio:
         * - Se angleDiff <= 60 graus: o carro está vindo pelo mesmo sentido que o motorista baniu anteriormente.
         *   Ignore o radar (retorna true).
         * - Se for maior que 60 graus: o carro está vindo do eixo oposto ou de uma via transversal.
         *   O bloqueio não se aplica; processe o alerta normalmente (retorna false).
         */
        /**
         * Fase 9.2 e 9.3: Filtro de Blacklist Híbrida (Vetor Direcional + Marreta Global).
         *
         * 1. Marreta Global (Fase 9.3):
         *    Se bannedApproaches contiver "${radarId}_GLOBAL" (ou ID numérico puro),
         *    o radar é ignorado incondicionalmente em TODAS as direções (retorna true).
         *
         * 2. Vetor Direcional (Fase 9.2):
         *    Busca todos os bloqueios direcionais no formato "${radarId}_${bannedBearing}".
         *    Calcula a diferença angular entre o vehicleBearing e o bannedBearing gravado:
         *    - Se angleDiff <= 60 graus: o carro está vindo pelo mesmo sentido banido -> ignora (retorna true).
         *    - Se angleDiff > 60 graus: o carro está em outro eixo -> alerta mantido (retorna false).
         */
        fun isApproachBanned(
            radarId: Long,
            vehicleBearing: Float?,
            bannedApproaches: Set<String>,
            toleranceDegrees: Double = BLACKLIST_CONE_TOLERANCE_DEGREES
        ): Boolean {
            val idStr = radarId.toString()
            val globalKey = "${radarId}_GLOBAL"

            // Prioridade máxima: Marreta Global (Fase 9.3) ou bloqueio legado por ID
            if (bannedApproaches.contains(globalKey) || bannedApproaches.contains(idStr)) {
                return true
            }

            val prefix = "${radarId}_"
            val matchingEntries = bannedApproaches.filter { it.startsWith(prefix) && it != globalKey }
            if (matchingEntries.isEmpty()) return false

            val currentBearing = vehicleBearing?.toDouble() ?: return false

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

        /**
         * Verifica se o radar deve ser filtrado/ignorado devido a:
         * 1. Blacklist Vetorial Direcional: vetor de aproximação banido com tolerância de 60° (Fase 9.2).
         * 2. Bloqueio Global do OSM: blockOsmRadars == true e radar.source == "OSM" (Fase 9).
         */
        fun shouldIgnoreRadar(
            radar: RadarEntity,
            blockOsmRadars: Boolean = false,
            blacklistedIds: Set<String> = emptySet(),
            vehicleBearing: Float? = null
        ): Boolean {
            if (isApproachBanned(radar.id, vehicleBearing, blacklistedIds)) {
                return true
            }
            if (blockOsmRadars && radar.source?.equals("OSM", ignoreCase = true) == true) {
                return true
            }
            return false
        }

        /**
         * Calcula a distância em metros entre duas coordenadas geográficas utilizando a fórmula de Haversine.
         * Pure Math: funciona tanto no Android quanto em testes unitários na JVM sem necessidade de mocks.
         */
        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0 // metros
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }

        /**
         * Calcula a menor diferença angular (0° a 180°) entre dois azimutes/direções de bússola.
         */
        fun calculateAngularDifference(angle1: Double, angle2: Double): Double {
            val norm1 = ((angle1 % 360.0) + 360.0) % 360.0
            val norm2 = ((angle2 % 360.0) + 360.0) % 360.0
            val diff = abs(norm1 - norm2)
            return if (diff > 180.0) 360.0 - diff else diff
        }

        /**
         * Conflito de Direção (Fase 10):
         * - Se ambos possuem direction_deg: a diferença angular entre eles é menor que 45 graus.
         * - Se um ou ambos NÃO possuem direction_deg: são considerados clones (pois o ponto sem direção
         *   já atua de forma radial 360º, tornando múltiplos pontos no mesmo raio redundantes).
         */
        fun hasDirectionConflict(bearingA: Double?, bearingB: Double?): Boolean {
            if (bearingA != null && bearingB != null) {
                val angleDiff = calculateAngularDifference(bearingA, bearingB)
                return angleDiff < DEDUPLICATION_DIRECTION_CONFLICT_DEGREES
            }
            return true
        }

        /**
         * Identifica se um radar B é considerado clone de um radar A (Fase 10).
         * Atende a todas as condições simultaneamente:
         * 1. Proximidade: Distância entre eles é menor que 35 metros (< 35.0).
         * 2. Mesma Regra: Possuem o mesmo limit_kmh (speedLimit).
         * 3. Mesmo Tipo: Possuem o mesmo kind (type, ex: ambos FIXED ou ambos TRAFFIC_LIGHT).
         * 4. Conflito de Direção: ambos com direção dif < 45°, ou pelo menos um sem direção.
         */
        fun areClones(a: RadarEntity, b: RadarEntity): Boolean {
            // 2. Mesma Regra: mesmo speedLimit (salva marginais lentas coladas em vias expressas)
            if (a.speedLimit != b.speedLimit) {
                return false
            }

            // 3. Mesmo Tipo: mesmo kind (FIXED, TRAFFIC_LIGHT, etc.)
            if (!a.type.trim().equals(b.type.trim(), ignoreCase = true)) {
                return false
            }

            // Bounding box prévio para alta performance antes do cálculo trigonométrico
            val latDiff = abs(a.latitude - b.latitude)
            if (latDiff > 0.0005) {
                return false
            }
            val lonDiff = abs(a.longitude - b.longitude)
            if (lonDiff > 0.0006) {
                return false
            }

            // 1. Proximidade: distância < 35 metros
            val distance = calculateDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (distance >= DEDUPLICATION_RADIUS_METERS) {
                return false
            }

            // 4. Conflito de Direção
            return hasDirectionConflict(a.bearing, b.bearing)
        }

        /**
         * Fase 10: Algoritmo de Deduplicação Robusto em RAM.
         *
         * Agrupa os radares usando uma "Impressão Digital" (Distância + Tipo + Limite de Velocidade)
         * para garantir que cruzamentos complexos não percam alertas vitais.
         *
         * Regra de Sobrevivência (O Campeão):
         * - Ao encontrar clones, mantenha na lista o radar que tiver a coluna direction_deg preenchida
         *   (pois ele permite o cálculo preciso do cone de visão para evitar falsos positivos nas ruas transversais).
         * - Se nenhum dos clones tiver direção, priorize o que tiver o source com o caractere '+' (dados enriquecidos).
         * - Em caso de empate total, mantenha o primeiro.
         */
        fun deduplicateRadars(radars: List<RadarEntity>): List<RadarEntity> {
            if (radars.size <= 1) return radars

            // Indexa e ordena os radares por prioridade de campeão:
            val indexedRadars = radars.withIndex().sortedWith { a, b ->
                val aHasBearing = a.value.bearing != null
                val bHasBearing = b.value.bearing != null
                if (aHasBearing != bHasBearing) {
                    return@sortedWith if (aHasBearing) -1 else 1
                }

                val aHasPlus = a.value.source?.contains("+") == true
                val bHasPlus = b.value.source?.contains("+") == true
                if (aHasPlus != bHasPlus) {
                    return@sortedWith if (aHasPlus) -1 else 1
                }

                a.index.compareTo(b.index)
            }

            val survivingIndexed = mutableListOf<IndexedValue<RadarEntity>>()

            for (candidate in indexedRadars) {
                val isCloneOfExisting = survivingIndexed.any { survivor ->
                    areClones(candidate.value, survivor.value)
                }
                if (!isCloneOfExisting) {
                    survivingIndexed.add(candidate)
                }
            }

            // Retorna os sobreviventes preservando a ordem original da lista
            return survivingIndexed.sortedBy { it.index }.map { it.value }
        }
    }

    /**
     * Encaminhador de instância para a função de deduplicação da Fase 10.
     */
    fun deduplicateRadars(radars: List<RadarEntity>): List<RadarEntity> {
        return Companion.deduplicateRadars(radars)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Iniciando RadarOverlayService")

        savedStateRegistryController.performRestore(null)

        radarDao = RadarDatabase.getDatabase(applicationContext).radarDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        preferencesManager = PreferencesManager.getInstance(applicationContext)
        _isNightMode.value = preferencesManager.isNightModeEnabled
        _isHudMode.value = preferencesManager.isHudModeEnabled

        initSoundPool()
        createNotificationChannel()
        startAsForegroundService()
        setupLocationCallback()
        setupOverlayWindow()

        _isRunning.value = true
        broadcastState(applicationContext, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            _isRunning.value = false
            preferencesManager.isServiceIntentionallyStopped = true
            broadcastState(applicationContext, false)
            _isGpsLost.value = false
            _closestRadarAlertDistance.value = PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
            stopGpsWatchdog()
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        preferencesManager.isServiceIntentionallyStopped = false
        broadcastState(applicationContext, true)
        startLocationUpdates()

        // Garante que o overlay seja inicializado se a permissão foi concedida após o onCreate
        if (!isOverlayAttached) {
            setupOverlayWindow()
        }

        // Fase 11: START_STICKY garante resiliência a suspensão e recuperação pelo Android
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destruindo RadarOverlayService e liberando recursos")
        _isRunning.value = false
        broadcastState(applicationContext, false)
        _isGpsLost.value = false
        _closestRadarAlertDistance.value = PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
        stopGpsWatchdog()
        stopLocationUpdates()
        releaseWakeLock()
        removeOverlayWindow()
        releaseSoundPool()
        serviceViewModelStore.clear()
    }

    /**
     * Inicializa o WindowManager e anexa o ComposeView com os ViewTreeOwners necessários.
     */
    private fun setupOverlayWindow() {
        if (isOverlayAttached) return

        // Verifica permissão de sobreposição (SYSTEM_ALERT_WINDOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permissão SYSTEM_ALERT_WINDOW não concedida. Janela flutuante não exibida.")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_FOCUSABLE garante que os toques passem direto para a multimídia do carro
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 50 // Distância do topo da tela da multimídia
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            // Atribuição obrigatória dos donos da árvore Compose em um Service
            setViewTreeLifecycleOwner(this@RadarOverlayService)
            setViewTreeViewModelStoreOwner(this@RadarOverlayService)
            setViewTreeSavedStateRegistryOwner(this@RadarOverlayService)

            setContent {
                RadarFloatingOverlayContent()
            }
        }

        try {
            windowManager?.addView(composeView, layoutParams)
            overlayComposeView = composeView
            isOverlayAttached = true
            Log.d(TAG, "ComposeView adicionado com sucesso ao WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar ComposeView ao WindowManager", e)
        }
    }

    /**
     * Remove o ComposeView do WindowManager no encerramento do serviço.
     */
    private fun removeOverlayWindow() {
        if (isOverlayAttached && overlayComposeView != null) {
            try {
                windowManager?.removeView(overlayComposeView)
                Log.d(TAG, "Overlay removido com sucesso do WindowManager.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover overlay do WindowManager", e)
            }
            overlayComposeView = null
            isOverlayAttached = false
        }
    }

    /**
     * Fase 9.3: Blacklist Híbrida (Vetor + Global via Long Press).
     * - global == false (Toque Rápido): bane o radar apenas no vetor direcional atual do carro.
     *   Chave: "${radarId}_${currentBearing}". Toast: "Radar banido neste sentido".
     * - global == true (Toque Longo / Long Press): bane o radar globalmente em TODAS as direções.
     *   Chave: "${radarId}_GLOBAL". Toast: "Radar banido em TODAS as direções".
     */
    fun banCurrentRadar(global: Boolean = false) {
        val currentNearby = _closestRadar.value ?: return
        val radarId = currentNearby.radar.id

        if (global) {
            preferencesManager.banRadarGlobally(radarId)
            Toast.makeText(this@RadarOverlayService, "Radar banido em TODAS as direções", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Radar $radarId banido GLOBALMENTE via banCurrentRadar(global = true). Janela flutuante apagada instantaneamente.")
        } else {
            // Captura o heading/bearing atual do veículo no milissegundo do clique
            val currentBearing = lastValidBearing ?: _currentLocation.value?.let { loc ->
                if (loc.hasBearing() && loc.bearing != 0.0f) loc.bearing else null
            } ?: 0.0f
            val normBearing = ((currentBearing.roundToInt() % 360) + 360) % 360
            preferencesManager.banApproach(radarId, normBearing.toDouble())
            Toast.makeText(this@RadarOverlayService, "Radar banido no vetor ~${normBearing}º", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Radar $radarId banido no vetor direcional $normBearing° via banCurrentRadar(global = false). Janela flutuante apagada instantaneamente.")
        }

        _nearbyRadars.value = _nearbyRadars.value.filter { it.radar.id != radarId }
        _closestRadar.value = null
    }

    /**
     * Adiciona o radar com seu vetor ou globalmente à Blacklist local e remove imediatamente o alerta da tela (Fase 9.3).
     */
    fun blacklistRadar(radarId: Long, bearing: Float? = null, global: Boolean = false) {
        if (global) {
            preferencesManager.banRadarGlobally(radarId)
            Toast.makeText(this@RadarOverlayService, "Radar banido em TODAS as direções", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Radar $radarId banido GLOBALMENTE.")
        } else {
            val currentBearing = bearing ?: lastValidBearing ?: _currentLocation.value?.let { loc ->
                if (loc.hasBearing() && loc.bearing != 0.0f) loc.bearing else null
            } ?: 0.0f
            val normBearing = ((currentBearing.roundToInt() % 360) + 360) % 360
            preferencesManager.banApproach(radarId, normBearing.toDouble())
            Toast.makeText(this@RadarOverlayService, "Radar banido no vetor ~${normBearing}º", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Radar $radarId banido no vetor direcional $normBearing°.")
        }

        val updatedNearby = _nearbyRadars.value.filter { it.radar.id != radarId }
        _nearbyRadars.value = updatedNearby
        val newClosest = updatedNearby.firstOrNull()
        _closestRadar.value = newClosest
        _closestRadarAlertDistance.value = newClosest?.alertDistanceMeters ?: PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
    }

    /**
     * UI Flutuante Reativa:
     * - Se closestRadar == null: invisível / vazia (não obstrui a tela nem toques).
     * - Se currentSpeedKmh <= closestRadar.speedLimit: janela pequena com fundo verde/cinza escuro.
     * - Se currentSpeedKmh > closestRadar.speedLimit: alerta progressivo (estático ou agressivo).
     */
    @Composable
    private fun RadarFloatingOverlayContent() {
        val closestRadarState by closestRadar.collectAsState()
        val speedState by currentSpeedKmh.collectAsState()
        val isGpsLostState by isGpsLost.collectAsState()
        val isNightMode by isNightMode.collectAsState()
        val isHudMode by isHudMode.collectAsState()
        val isDarkMode = isNightMode || isHudMode

        // Efeito HUD (Head-Up Display): APENAS espelha horizontalmente se o modo HUD estiver ativo!
        // O Modo Noturno padrão mantém scaleX = 1f para visão direta na multimídia ou suporte
        Box(
            modifier = Modifier.then(
                if (isHudMode) Modifier.graphicsLayer { scaleX = -1f } else Modifier
            )
        ) {
            // Se o sinal de GPS for perdido (túneis ou zonas mortas): exibe ícone cinza discreto
            AnimatedVisibility(
                visible = isGpsLostState,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                GpsLostOverlay(isDarkMode = isDarkMode)
            }

            AnimatedVisibility(
                visible = !isGpsLostState && closestRadarState != null,
                enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
                exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f)
            ) {
                closestRadarState?.let { nearby ->
                    val radar = nearby.radar
                    val speedLimit = radar.speedLimit
                    val distance = nearby.distanceMeters.toInt()
                    val alertDistance = nearby.alertDistanceMeters
                    val isOverSpeed = speedState > speedLimit

                    val haptic = LocalHapticFeedback.current

                    val onBanClick = {
                        banCurrentRadar(global = false)
                    }
                    val onBanLongClick = {
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } catch (e: Exception) {
                            Log.w(TAG, "Haptic feedback não disponível", e)
                        }
                        banCurrentRadar(global = true)
                    }

                    val baseAlertTime = if (::preferencesManager.isInitialized) preferencesManager.baseAlertTime else PreferencesManager.DEFAULT_BASE_ALERT_TIME
                    val aggressiveAlertTime = if (::preferencesManager.isInitialized) preferencesManager.aggressiveAlertTime else PreferencesManager.DEFAULT_AGGRESSIVE_ALERT_TIME

                    // Fase 11: Se timeToCollision <= aggressive_alert_time E currentSpeedKmh > radar.speedLimit -> Alerta Agressivo
                    val isAggressive = isOverSpeed && (nearby.timeToCollisionSec <= aggressiveAlertTime.toDouble() || nearby.isAggressiveAlert)

                    if (!isOverSpeed) {
                        // 1. Estado Verde/Neutro (Seguro): Se currentSpeed <= radar.speedLimit.
                        // Card normal (tamanho padrão), cor verde ou cinza escuro.
                        NormalRadarOverlay(
                            distanceMeters = distance,
                            speedLimit = speedLimit,
                            currentSpeed = speedState.toInt(),
                            radarType = radar.type,
                            isDarkMode = isDarkMode,
                            source = radar.source,
                            directionDeg = radar.bearing,
                            onBlacklistClick = onBanClick,
                            onBlacklistLongClick = onBanLongClick
                        )
                    } else if (isAggressive) {
                        // 3. Estado Vermelho Agressivo (Perigo Iminente - Frenagem):
                        // Se currentSpeed > radar.speedLimit E timeToCollision <= aggressive_alert_time.
                        // O layout muda para expansivo, ocupando mais espaço. O fundo pisca agressivamente
                        // (via InfiniteTransition de cor/opacidade) com o texto chamativo pedindo para reduzir.
                        AggressiveRadarOverlay(
                            distanceMeters = distance,
                            speedLimit = speedLimit,
                            currentSpeed = speedState.toInt(),
                            radarType = radar.type,
                            isDarkMode = isDarkMode,
                            source = radar.source,
                            directionDeg = radar.bearing,
                            onBlacklistClick = onBanClick,
                            onBlacklistLongClick = onBanLongClick
                        )
                    } else {
                        // 2. Estado Vermelho Estático (Atenção - Alerta Inicial):
                        // Se currentSpeed > radar.speedLimit porém timeToCollision > aggressive_alert_time.
                        // O card tem o mesmo tamanho compacto do estado verde, mas com o fundo Vermelho Sólido
                        // (sem piscar nem alterar escala). Texto: "Acima do Limite".
                        WarningStaticRadarOverlay(
                            distanceMeters = distance,
                            speedLimit = speedLimit,
                            currentSpeed = speedState.toInt(),
                            radarType = radar.type,
                            isDarkMode = isDarkMode,
                            source = radar.source,
                            directionDeg = radar.bearing,
                            onBlacklistClick = onBanClick,
                            onBlacklistLongClick = onBanLongClick
                        )
                    }
                }
            }
        }
    }

    /**
     * Fase 12: Exibe a fonte da base e a seta direcional cadastrada no banco de dados.
     * Se directionDeg for nulo, exibe "• 360°" para indicar radar radial.
     * Se directionDeg existir, aplica Modifier.rotate(directionDeg.toFloat()) na seta (Norte = 0°).
     */
    @Composable
    private fun RadarDirectionAndSourceBadge(
        source: String?,
        directionDeg: Double?,
        textColor: Color = Color.White.copy(alpha = 0.6f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Fonte: ${source ?: "Desconhecida"}",
                fontSize = 10.sp,
                color = textColor,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.width(6.dp))

            if (directionDeg != null) {
                val roundedDeg = ((directionDeg.roundToInt() % 360) + 360) % 360
                Icon(
                    imageVector = Icons.Default.North,
                    contentDescription = "Sentido $roundedDeg°",
                    modifier = Modifier
                        .size(11.dp)
                        .rotate(roundedDeg.toFloat()),
                    tint = textColor
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "$roundedDeg°",
                    fontSize = 10.sp,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = "• 360°",
                    fontSize = 10.sp,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    /**
     * Estado Vermelho Estático (Atenção - Alerta Inicial):
     * Usado quando currentSpeed > radar.speedLimit E timeToCollision > aggressive_alert_time.
     * Possui exatamente o mesmo tamanho compacto do NormalRadarOverlay, porém com fundo Vermelho Sólido
     * estático (sem piscar nem animar escala) e o texto "Acima do Limite".
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun WarningStaticRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        directionDeg: Double? = null,
        onBlacklistClick: () -> Unit,
        onBlacklistLongClick: (() -> Unit)? = null
    ) {
        val (typeIcon, _) = getRadarTypeInfo(radarType)
        val backgroundColor = if (isDarkMode) Color(0xFF8B0000) else Color(0xFFD50000)
        val borderColor = if (isDarkMode) Color(0xFFFF5252) else Color(0xFFFFFFFF)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            ),
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp)
                .shadow(if (isDarkMode) 0.dp else 12.dp, RoundedCornerShape(20.dp))
                .border(2.dp, borderColor, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Informações do radar (esquerda)
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Placa de trânsito regulamentar
                        SpeedLimitBadge(speedLimit = speedLimit, sizeDp = 46)

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = typeIcon,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Acima do Limite",
                                    color = Color(0xFFFFEB3B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${distanceMeters}m",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Atual: $currentSpeed km/h",
                                color = Color(0xFFFFCDD2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Transparência de Fonte (Fase 9) e Setas Direcionais (Fase 12)
                    RadarDirectionAndSourceBadge(
                        source = source,
                        directionDeg = directionDeg,
                        textColor = Color.White.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Divisor vertical sutil
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Botão Gigante de Blacklist Híbrida (Fase 9.1 / 9.3: 72.dp com Toque Rápido e Toque Longo)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .combinedClickable(
                            onClick = onBlacklistClick,
                            onLongClick = onBlacklistLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Ignorar radar",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "IGNORAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }

    /**
     * Card discreto com ícone cinza para sinalização de GPS perdido em túneis ou zonas mortas.
     */
    @Composable
    private fun GpsLostOverlay(isDarkMode: Boolean) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xDD37474F)
            ),
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp)
                .shadow(if (isDarkMode) 0.dp else 6.dp, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "📡", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Sinal GPS Perdido",
                    color = Color(0xFFECEFF1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    /**
     * Janela pequena e discreta (verde/cinza escuro) para quando o carro está dentro do limite.
     * Exibe o ícone específico do tipo de radar (🚦 Semáforo, 📸 Fixo, 🚶 Lombada, ⏱️ Trecho).
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun NormalRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        directionDeg: Double? = null,
        onBlacklistClick: () -> Unit,
        onBlacklistLongClick: (() -> Unit)? = null
    ) {
        val (typeIcon, typeLabel) = getRadarTypeInfo(radarType)
        val backgroundColor = if (isDarkMode) Color(0xFF000000) else Color(0xFF1E2822).copy(alpha = 0.94f)
        val borderColor = if (isDarkMode) Color(0xFF00FF66) else Color(0xFF4CAF50)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            ),
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp)
                .shadow(if (isDarkMode) 0.dp else 12.dp, RoundedCornerShape(20.dp))
                .border(2.dp, borderColor, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Informações do radar (esquerda)
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Placa de trânsito regulamentar (círculo branco, borda vermelha)
                        SpeedLimitBadge(speedLimit = speedLimit, sizeDp = 44)

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = typeIcon,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = typeLabel,
                                    color = if (isDarkMode) Color(0xFF00FF66) else Color(0xFF81C784),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "${distanceMeters}m",
                                color = if (isDarkMode) Color(0xFF00FF66) else Color(0xFF4CAF50),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Atual: $currentSpeed km/h",
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Transparência de Fonte (Fase 9) e Setas Direcionais (Fase 12)
                    RadarDirectionAndSourceBadge(
                        source = source,
                        directionDeg = directionDeg,
                        textColor = Color.White.copy(alpha = 0.55f)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Divisor vertical sutil
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Botão Gigante de Blacklist Híbrida (Fase 9.1 / 9.3: 72.dp com Toque Rápido e Toque Longo)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .combinedClickable(
                            onClick = onBlacklistClick,
                            onLongClick = onBlacklistLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Ignorar radar",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "IGNORAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }

    /**
     * Layout maior com fundo vermelho piscando agressivamente via InfiniteTransition.
     * Exibe o ícone e rótulo do tipo de radar com aviso incisivo de redução.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AggressiveRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        directionDeg: Double? = null,
        onBlacklistClick: () -> Unit,
        onBlacklistLongClick: (() -> Unit)? = null
    ) {
        val (typeIcon, typeLabel) = getRadarTypeInfo(radarType)
        val infiniteTransition = rememberInfiniteTransition(label = "AggressiveAlertTransition")

        // Animação de cor de fundo alternando entre vermelho vivo e vermelho escuro
        val animatedBackgroundColor by infiniteTransition.animateColor(
            initialValue = if (isDarkMode) Color(0xFF000000) else Color(0xFFD50000),
            targetValue = if (isDarkMode) Color(0xFF4A0000) else Color(0xFF6B0000),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 250, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AggressiveBlinkColor"
        )

        // Pulso leve de escala para reforçar urgência na visão periférica do condutor
        val scalePulse by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AggressiveScalePulse"
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = animatedBackgroundColor
            ),
            modifier = Modifier
                .wrapContentSize()
                .scale(scalePulse)
                .padding(10.dp)
                .shadow(if (isDarkMode) 0.dp else 20.dp, RoundedCornerShape(24.dp))
                .border(3.dp, if (isDarkMode) Color(0xFFFF1744) else Color(0xFFFFFFFF), RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Tipo de Radar no topo
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = typeIcon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$typeLabel - REDUZA!",
                            color = Color(0xFFFF8A80),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Texto de alerta principal bem visível
                    Text(
                        text = "LIMITE $speedLimit km/h",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Placa com velocidade limite
                        SpeedLimitBadge(speedLimit = speedLimit, sizeDp = 50)

                        Spacer(modifier = Modifier.width(18.dp))

                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "$currentSpeed km/h",
                                color = Color(0xFFFFEB3B), // Amarelo alto contraste
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Radar a ${distanceMeters}m",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Transparência de Fonte (Fase 9) e Setas Direcionais (Fase 12)
                    RadarDirectionAndSourceBadge(
                        source = source,
                        directionDeg = directionDeg,
                        textColor = Color.White.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Divisor vertical sutil
                Box(
                    modifier = Modifier
                        .height(96.dp)
                        .width(1.5.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Botão Gigante de Blacklist Híbrida (Fase 9.1 / 9.3: 72.dp com Toque Rápido e Toque Longo)
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .combinedClickable(
                            onClick = onBlacklistClick,
                            onLongClick = onBlacklistLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Ignorar radar",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "IGNORAR",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White.copy(alpha = 0.95f)
                        )
                    }
                }
            }
        }
    }

    /**
     * Placa regulamentar de velocidade no formato padrão brasileiro (círculo com borda vermelha).
     */
    @Composable
    private fun SpeedLimitBadge(speedLimit: Int, sizeDp: Int) {
        Surface(
            shape = CircleShape,
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(3.5.dp, Color(0xFFD50000)),
            modifier = Modifier.size(sizeDp.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$speedLimit",
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontSize = (sizeDp * 0.42).sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    /**
     * Inicializa o SoundPool para carregar beep.mp3 de res/raw sem vazamento de memória.
     */
    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                isSoundLoaded = true
                Log.d(TAG, "Áudio beep.mp3 carregado com sucesso (sampleId=$sampleId)")
            } else {
                Log.e(TAG, "Erro ao carregar beep.mp3 (status=$status)")
            }
        }

        try {
            beepSoundId = soundPool?.load(this, R.raw.beep, 1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao solicitar carregamento de R.raw.beep", e)
        }
    }

    /**
     * Libera o SoundPool ao destruir o serviço para prevenir memory leaks.
     */
    private fun releaseSoundPool() {
        soundPool?.release()
        soundPool = null
        isSoundLoaded = false
        radarAlertCooldowns.clear()
    }

    /**
     * Executa o disparo do som de alerta via SoundPool.
     * Silencia o som se isSoundEnabled == false em PreferencesManager.
     */
    fun playBeep() {
        if (!preferencesManager.isSoundEnabled) {
            Log.d(TAG, "Alerta sonoro mutado pelo usuário em PreferencesManager.")
            return
        }
        if (isSoundLoaded && beepSoundId != 0) {
            soundPool?.play(beepSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    /**
     * Configura o FusedLocationProviderClient para coletar a localização a cada 1000ms
     * com prioridade PRIORITY_HIGH_ACCURACY.
     */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return

                lastGpsUpdateTimestamp = System.currentTimeMillis()
                if (_isGpsLost.value) {
                    _isGpsLost.value = false
                    Log.i(TAG, "Sinal GPS restabelecido.")
                }

                lifecycleScope.launch {
                    processLocation(location)
                }
            }
        }
    }

    /**
     * Inicia as atualizações de localização a cada 1 segundo (1000ms).
     */
    private fun startLocationUpdates() {
        val hasFinePermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarsePermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            Log.w(TAG, "Permissão de localização não concedida para iniciar atualizações.")
            return
        }

        acquireWakeLock()
        lastGpsUpdateTimestamp = System.currentTimeMillis()
        requestLocationUpdatesWithInterval(1000L)
        startGpsWatchdog()
    }

    /**
     * Ajusta a frequência das atualizações de localização do FusedLocationProviderClient.
     * Permite alternar entre 1000ms (condução normal) e 5000ms (modo standby em paradas > 5min).
     */
    private fun requestLocationUpdatesWithInterval(intervalMs: Long) {
        val hasFinePermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarsePermission = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            Log.w(TAG, "Permissão de localização não concedida para ajustar taxa de atualização.")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Frequência de GPS ajustada para ${intervalMs}ms.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de segurança ao solicitar atualizações de localização", e)
        }
    }

    /**
     * Inicia o watchdog de timeout do sinal GPS.
     * Se nenhuma leitura for recebida por mais de 5000ms, assume perda de sinal (túneis / zonas mortas).
     */
    private fun startGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000L)
                val now = System.currentTimeMillis()
                val allowedTimeout = if (isStandbyMode) 15_000L else GPS_TIMEOUT_MS
                if (lastGpsUpdateTimestamp > 0L && (now - lastGpsUpdateTimestamp > allowedTimeout)) {
                    if (!_isGpsLost.value) {
                        Log.w(TAG, "Sinal GPS perdido detectado (última leitura há ${now - lastGpsUpdateTimestamp}ms). Limpando alerta ativo.")
                        _isGpsLost.value = true
                        _closestRadar.value = null
                        _nearbyRadars.value = emptyList()
                        _closestRadarAlertDistance.value = PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
                    }
                }
            }
        }
    }

    /**
     * Cancela o watchdog do GPS ao encerrar as atualizações.
     */
    private fun stopGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
    }

    /**
     * Para as atualizações do GPS ao destruir o serviço.
     */
    private fun stopLocationUpdates() {
        stopGpsWatchdog()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    /**
     * Gerencia o cache espacial em memória RAM (Anti-Superaquecimento):
     * O DAO só é consultado em raio de 15 km se cachedRadars estiver vazio ou se o veículo
     * se deslocou mais de 10 km em relação à última busca.
     */
    suspend fun getOrUpdateCachedRadars(location: Location): List<RadarEntity> {
        if (shouldRefreshCache(location, lastQueryLocation, cachedRadars.size)) {
            Log.d(TAG, "Cache de radares em RAM desatualizado ou vazio. Consultando DAO (raio de 15 km)...")
            val totalInDb = radarDao.getRadarCount()
            if (totalInDb == 0) {
                Log.w(TAG, "Banco de radares vazio! Populando base de fábrica dos assets imediatamente...")
                RadarDatabase.populateFactoryRadarsSync(applicationContext, radarDao)
            }
            val rawRadars = radarDao.getRadarsWithinRadius(
                latitude = location.latitude,
                longitude = location.longitude,
                radiusInMeters = CACHE_RADIUS_METERS
            )
            // Fase 10: Deduplicação Direcional e Heurística de Tipo/Velocidade em RAM
            cachedRadars = deduplicateRadars(rawRadars)
            lastQueryLocation = Location(location)
            Log.d(TAG, "Cache espacial atualizado com ${cachedRadars.size} radares (após deduplicação de ${rawRadars.size} brutos).")
        }
        return cachedRadars
    }

    /**
     * Processa a localização atual do veículo:
     * 1. Converte location.speed (m/s) para km/h (* 3.6).
     * 2. Modo Standby: reduz a taxa para 5s se o carro ficar parado > 5min, restaurando para 1s em movimento.
     * 3. Caching Espacial em RAM: itera apenas sobre cachedRadars (raio 15km atualizado a cada 10km).
     * 4. Filtra por alcance dinâmico de distância (250m cidade / 500m rodovia).
     * 5. Aplica o Cone de Visão Frontal (180 graus, angleDiff < 90).
     * 6. Aplica o Sentido da Via (+/- 30 graus para radares direcionais).
     * 7. Dispara o alerta sonoro se estiver acima do limiar e o som não estiver mutado.
     */
    suspend fun processLocation(location: Location) = withContext(Dispatchers.IO) {
        val speedKmh = if (location.hasSpeed()) {
            location.speed * 3.6f
        } else {
            0f
        }

        // Modo Standby em Parada Prolongada (speed == 0 por mais de 5 minutos contínuos)
        val now = System.currentTimeMillis()
        if (speedKmh < 1.0f) {
            if (stoppedStartTime == null) {
                stoppedStartTime = now
            } else if (!isStandbyMode && (now - stoppedStartTime!! >= STANDBY_TIMEOUT_MS)) {
                Log.i(TAG, "Veículo parado há mais de 5 minutos. Ativando modo Standby (intervalo de 5s no GPS).")
                isStandbyMode = true
                requestLocationUpdatesWithInterval(5000L)
            }
        } else {
            stoppedStartTime = null
            if (isStandbyMode) {
                Log.i(TAG, "Movimento detectado ($speedKmh km/h). Restaurando taxa de GPS para 1s.")
                isStandbyMode = false
                requestLocationUpdatesWithInterval(1000L)
            }
        }

        // Atualiza a direção (heading/bearing) estimada do veículo:
        // 1. Se o GPS fornecer bearing válido e velocidade > 2 km/h, usa o bearing nativo
        if (location.hasBearing() && speedKmh >= 2.0f && location.bearing != 0.0f) {
            lastValidBearing = location.bearing
            lastLocationForBearing = Location(location)
        } else {
            // 2. Se o GPS não tiver bearing (ou velocidade baixa em fila/semáforo),
            // calcula o vetor de deslocamento entre coordenadas consecutivas se o carro andou >= 3 metros
            val prevLoc = lastLocationForBearing
            if (prevLoc != null && location.distanceTo(prevLoc) >= 3.0f) {
                val computedBearing = prevLoc.bearingTo(location)
                lastValidBearing = if (computedBearing < 0) computedBearing + 360f else computedBearing
                lastLocationForBearing = Location(location)
            } else if (prevLoc == null) {
                lastLocationForBearing = Location(location)
            }
        }
        val effectiveVehicleBearing = lastValidBearing

        // Fase 11: Configurações mestre de tempo TTC (Tempo de Colisão)
        val baseAlertTime = preferencesManager.baseAlertTime
        val aggressiveAlertTime = preferencesManager.aggressiveAlertTime

        // Fase 9 e 9.2: Filtro Anti-Falsos Positivos (OSM e Blacklist Vetorial Direcional)
        val blockOsm = preferencesManager.blockOsmRadars
        val bannedApproaches = preferencesManager.bannedApproaches
        val vehicleBearingForCheck = effectiveVehicleBearing ?: if (location.hasBearing() && location.bearing != 0.0f) location.bearing else null

        _currentLocation.value = location
        _currentSpeedKmh.value = speedKmh

        // Fase 11: Matemática Dinâmica (TTC)
        // Se o carro estiver a menos de 10 km/h, assume 10 km/h para evitar divisão por zero ou avisos infinitos
        val effectiveSpeedKmh = if (speedKmh < 10f) 10f else speedKmh
        val speedMs = effectiveSpeedKmh / 3.6f

        // Curva de tempo variável: mais segundos na rodovia, menos na cidade
        val dynamicAlertLimit = calculateDynamicAlertLimit(speedKmh, baseAlertTime)

        // Caching Espacial em RAM (Anti-Superaquecimento): itera apenas sobre cachedRadars
        val candidates = getOrUpdateCachedRadars(location)

        val radarLocation = Location("radar_candidate")
        val matchingRadars = mutableListOf<NearbyRadar>()
        var shouldTriggerSound = false

        for (radar in candidates) {
            // Fase 9.2: Primeira regra de filtro - Blacklist Vetorial Direcional (Cone de Bloqueio de 60°)
            if (isApproachBanned(radar.id, vehicleBearingForCheck, bannedApproaches)) {
                continue
            }

            if (blockOsm && radar.source?.equals("OSM", ignoreCase = true) == true) {
                continue
            }

            radarLocation.latitude = radar.latitude
            radarLocation.longitude = radar.longitude

            val distanceMeters = location.distanceTo(radarLocation)

            // Fase 11: timeToCollision = distanceMeters / speedMs
            val timeToCollision = distanceMeters / speedMs

            // Regra de Disparo TTC: timeToCollision <= dynamicAlertLimit
            if (timeToCollision <= dynamicAlertLimit) {
                // 1. Verificação de Ângulo Relativo: Cone de Visão Frontal (170 graus à frente)
                // Descarta imediatamente se angleDiff >= 85 (radar ultrapassado ou nas costas do carro)
                val isFront = isInFrontOfVehicle(location, radarLocation, effectiveVehicleBearing)

                if (isFront) {
                    // 2. Verificação de Sentido da Via (+/- 30 graus para radares com bearing não-nulo)
                    val inDirection = isSameDirection(
                        vehicleBearing = effectiveVehicleBearing ?: location.bearing,
                        radarBearing = radar.bearing,
                        toleranceDegrees = BEARING_TOLERANCE_DEGREES
                    )

                    if (inDirection) {
                        val isAggressive = (timeToCollision <= aggressiveAlertTime.toDouble()) && (speedKmh > radar.speedLimit)
                        matchingRadars.add(
                            NearbyRadar(
                                radar = radar,
                                distanceMeters = distanceMeters,
                                alertDistanceMeters = (dynamicAlertLimit * speedMs).toFloat(),
                                timeToCollisionSec = timeToCollision.toDouble(),
                                isAggressiveAlert = isAggressive
                            )
                        )

                        val warnOnlyOverSpeed = preferencesManager.warnOnlyOverSpeed
                        val alertSpeedThreshold = if (warnOnlyOverSpeed) {
                            radar.speedLimit.toFloat()
                        } else {
                            0.9f * radar.speedLimit
                        }

                        if (speedKmh >= alertSpeedThreshold) {
                            val lastAlertTime = radarAlertCooldowns[radar.id] ?: 0L
                            if (now - lastAlertTime >= COOLDOWN_DURATION_MS) {
                                radarAlertCooldowns[radar.id] = now
                                if (preferencesManager.isSoundEnabled) {
                                    shouldTriggerSound = true
                                    Log.d(TAG, "ALERTA SONORO: Radar ID ${radar.id} (${radar.speedLimit} km/h) a ${distanceMeters.toInt()}m (TTC: ${timeToCollision.toInt()}s). Vel: ${speedKmh.toInt()} km/h")
                                }
                            }
                        }
                    }
                }
            }
        }

        val sortedAlerts = matchingRadars.sortedBy { it.distanceMeters }
        _nearbyRadars.value = sortedAlerts
        val closest = sortedAlerts.firstOrNull()
        _closestRadar.value = closest
        _closestRadarAlertDistance.value = closest?.alertDistanceMeters ?: (dynamicAlertLimit * speedMs).toFloat()

        if (shouldTriggerSound) {
            playBeep()
        }
    }

    /**
     * Método público de proximidade para consulta direta de radares no alcance dinâmico e na mesma direção.
     */
    suspend fun checkProximity(location: Location): List<RadarEntity> {
        return checkProximityWithDistance(location).map { it.radar }
    }

    /**
     * Retorna a lista de radares dentro da distância dinâmica de alerta na mesma direção com a distância calculada.
     * Utiliza o cache espacial em memória RAM para prevenir superaquecimento.
     */
    suspend fun checkProximityWithDistance(
        location: Location,
        customSpeedKmh: Float? = null,
        customBaseAlertTime: Int? = null,
        customAggressiveAlertTime: Int? = null,
        customBlockOsm: Boolean? = null,
        customBlacklistedIds: Set<String>? = null,
        customDistLowSpeed: Int? = null,
        customDistCity: Int? = null,
        customDistExpress: Int? = null,
        customDistHighway: Int? = null
    ): List<NearbyRadar> = withContext(Dispatchers.IO) {
        val speedKmh = customSpeedKmh ?: if (location.hasSpeed()) location.speed * 3.6f else 0f
        val baseAlertTime = customBaseAlertTime ?: if (::preferencesManager.isInitialized) preferencesManager.baseAlertTime else PreferencesManager.DEFAULT_BASE_ALERT_TIME
        val aggressiveAlertTime = customAggressiveAlertTime ?: if (::preferencesManager.isInitialized) preferencesManager.aggressiveAlertTime else PreferencesManager.DEFAULT_AGGRESSIVE_ALERT_TIME

        val blockOsm = customBlockOsm ?: if (::preferencesManager.isInitialized) preferencesManager.blockOsmRadars else true
        val bannedApproaches = customBlacklistedIds ?: if (::preferencesManager.isInitialized) preferencesManager.bannedApproaches else emptySet()

        // Fase 11: A Matemática Dinâmica (TTC)
        val effectiveSpeedKmh = if (speedKmh < 10f) 10f else speedKmh
        val speedMs = effectiveSpeedKmh / 3.6f
        val dynamicAlertLimit = calculateDynamicAlertLimit(speedKmh, baseAlertTime)

        // Caching Espacial em RAM (Anti-Superaquecimento): itera apenas sobre cachedRadars
        val candidates = getOrUpdateCachedRadars(location)

        val radarLocation = Location("radar_check")
        val matchingRadars = mutableListOf<NearbyRadar>()

        val effectiveVehicleBearing = lastValidBearing ?: if (location.hasBearing() && location.bearing != 0.0f) location.bearing else null

        for (radar in candidates) {
            // Fase 9.2: Primeira regra de filtro - Blacklist Vetorial Direcional (Cone de Bloqueio de 60°)
            if (isApproachBanned(radar.id, effectiveVehicleBearing, bannedApproaches)) {
                continue
            }

            if (blockOsm && radar.source?.equals("OSM", ignoreCase = true) == true) {
                continue
            }

            radarLocation.latitude = radar.latitude
            radarLocation.longitude = radar.longitude

            val distanceMeters = location.distanceTo(radarLocation)
            val timeToCollision = distanceMeters / speedMs

            if (timeToCollision <= dynamicAlertLimit) {
                // 1. Verificação de Ângulo Relativo: Cone de Visão Frontal (170 graus)
                val isFront = isInFrontOfVehicle(location, radarLocation, effectiveVehicleBearing)

                if (isFront) {
                    // 2. Verificação de Sentido da Via
                    val matchesDirection = isSameDirection(
                        vehicleBearing = effectiveVehicleBearing ?: location.bearing,
                        radarBearing = radar.bearing,
                        toleranceDegrees = BEARING_TOLERANCE_DEGREES
                    )

                    if (matchesDirection) {
                        val isAggressive = (timeToCollision <= aggressiveAlertTime.toDouble()) && (speedKmh > radar.speedLimit)
                        matchingRadars.add(
                            NearbyRadar(
                                radar = radar,
                                distanceMeters = distanceMeters,
                                alertDistanceMeters = (dynamicAlertLimit * speedMs).toFloat(),
                                timeToCollisionSec = timeToCollision.toDouble(),
                                isAggressiveAlert = isAggressive
                            )
                        )
                    }
                }
            }
        }

        matchingRadars.sortedBy { it.distanceMeters }
    }

    /**
     * Inicia o Foreground Service com notificação contínua.
     */
    private fun startAsForegroundService() {
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Cria a notificação contínua do Foreground Service com Intent para reabertura da MainActivity.
     */
    private fun buildForegroundNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, launchIntent, flags)
        } else {
            null
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RadarLite Ativo")
            .setContentText("Monitorando radares e velocidade em tempo real...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()
    }

    /**
     * Cria o canal de notificações para Android 8.0+ (Oreo).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente do serviço de rastreamento de radares"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
