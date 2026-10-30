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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.ComposeView
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

/**
 * Radar próximo identificado com cálculo de distância e alcance de alerta configurado.
 */
data class NearbyRadar(
    val radar: RadarEntity,
    val distanceMeters: Float,
    val alertDistanceMeters: Float = 500f
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

        // Intervalo de cooldown para não repetir o alerta sonoro para o mesmo radar (15s)
        const val COOLDOWN_DURATION_MS = 15_000L

        // Tolerância angular de direção padrão (+/- 30 graus)
        const val BEARING_TOLERANCE_DEGREES = 30.0

        // Constantes do Caching Espacial em RAM (Anti-Superaquecimento)
        const val CACHE_RADIUS_METERS = 15_000.0 // 15 km
        const val CACHE_EXPIRATION_DISTANCE_METERS = 3_000f // 3 km (margem segura de revalidação)

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
         * Inicia o serviço em primeiro plano.
         */
        fun start(context: Context) {
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
         * Para o serviço de rastreamento.
         */
        fun stop(context: Context) {
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
         * Verifica se o radar deve ser filtrado/ignorado devido a:
         * 1. Blacklist Local: radar.id presente no conjunto de IDs banidos pelo usuário (Fase 9).
         * 2. Bloqueio Global do OSM: blockOsmRadars == true e radar.source == "OSM" (Fase 9).
         */
        fun shouldIgnoreRadar(
            radar: RadarEntity,
            blockOsmRadars: Boolean = false,
            blacklistedIds: Set<String> = emptySet()
        ): Boolean {
            if (blacklistedIds.contains(radar.id.toString())) {
                return true
            }
            if (blockOsmRadars && radar.source?.equals("OSM", ignoreCase = true) == true) {
                return true
            }
            return false
        }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            _isRunning.value = false
            _isGpsLost.value = false
            _closestRadarAlertDistance.value = PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
            stopGpsWatchdog()
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        startLocationUpdates()

        // Garante que o overlay seja inicializado se a permissão foi concedida após o onCreate
        if (!isOverlayAttached) {
            setupOverlayWindow()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destruindo RadarOverlayService e liberando recursos")
        _isRunning.value = false
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
     * Pega o ID do closestRadar atual, chama o preferencesManager.banRadar,
     * e em seguida força _closestRadar.value = null para apagar a janela instantaneamente (Fase 9).
     */
    fun banCurrentRadar() {
        val currentNearby = _closestRadar.value ?: return
        val radarId = currentNearby.radar.id
        preferencesManager.banRadar(radarId)
        _nearbyRadars.value = _nearbyRadars.value.filter { it.radar.id != radarId }
        _closestRadar.value = null
        Toast.makeText(this@RadarOverlayService, "Radar movido para a Blacklist", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Radar $radarId banido com sucesso via banCurrentRadar(). Janela flutuante apagada instantaneamente.")
    }

    /**
     * Adiciona o ID do radar à Blacklist local e remove imediatamente o alerta da tela (Fase 9).
     */
    fun blacklistRadar(radarId: Long) {
        preferencesManager.banRadar(radarId)
        val updatedNearby = _nearbyRadars.value.filter { it.radar.id != radarId }
        _nearbyRadars.value = updatedNearby
        val newClosest = updatedNearby.firstOrNull()
        _closestRadar.value = newClosest
        _closestRadarAlertDistance.value = newClosest?.alertDistanceMeters ?: PreferencesManager.DEFAULT_DIST_HIGHWAY.toFloat()
        Toast.makeText(this@RadarOverlayService, "Radar movido para a Blacklist", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Radar $radarId adicionado à Blacklist local com sucesso e ocultado da tela.")
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

                    val onBanClick = {
                        banCurrentRadar()
                    }

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
                            onBlacklistClick = onBanClick
                        )
                    } else if (nearby.distanceMeters > (alertDistance / 2f)) {
                        // 2. Estado Vermelho Estático (Atenção - Alerta Inicial):
                        // Se currentSpeed > radar.speedLimit E currentDistance > (alertDistance / 2).
                        // O card tem o mesmo tamanho compacto do estado verde, mas com o fundo Vermelho Sólido
                        // (sem piscar nem alterar escala). Texto: "Acima do Limite".
                        WarningStaticRadarOverlay(
                            distanceMeters = distance,
                            speedLimit = speedLimit,
                            currentSpeed = speedState.toInt(),
                            radarType = radar.type,
                            isDarkMode = isDarkMode,
                            source = radar.source,
                            onBlacklistClick = onBanClick
                        )
                    } else {
                        // 3. Estado Vermelho Agressivo (Perigo Iminente - Frenagem):
                        // Se currentSpeed > radar.speedLimit E currentDistance <= (alertDistance / 2).
                        // O layout muda para expansivo, ocupando mais espaço. O fundo pisca agressivamente
                        // (via InfiniteTransition de cor/opacidade) com o texto chamativo pedindo para reduzir.
                        AggressiveRadarOverlay(
                            distanceMeters = distance,
                            speedLimit = speedLimit,
                            currentSpeed = speedState.toInt(),
                            radarType = radar.type,
                            isDarkMode = isDarkMode,
                            source = radar.source,
                            onBlacklistClick = onBanClick
                        )
                    }
                }
            }
        }
    }

    /**
     * Estado Vermelho Estático (Atenção - Alerta Inicial):
     * Usado quando currentSpeed > radar.speedLimit E currentDistance > (alertDistance / 2).
     * Possui exatamente o mesmo tamanho compacto do NormalRadarOverlay, porém com fundo Vermelho Sólido
     * estático (sem piscar nem animar escala) e o texto "Acima do Limite".
     */
    @Composable
    private fun WarningStaticRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        onBlacklistClick: () -> Unit
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

                    // Transparência de Fonte (Fase 9)
                    Text(
                        text = "Fonte: ${source ?: "Desconhecida"}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal
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

                // Botão Gigante de Blacklist (Fase 9.1: 72.dp)
                Surface(
                    onClick = onBlacklistClick,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.size(72.dp)
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
    @Composable
    private fun NormalRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        onBlacklistClick: () -> Unit
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

                    // Transparência de Fonte (Fase 9)
                    Text(
                        text = "Fonte: ${source ?: "Desconhecida"}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        fontWeight = FontWeight.Normal
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

                // Botão Gigante de Blacklist (Fase 9.1: 72.dp)
                Surface(
                    onClick = onBlacklistClick,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.size(72.dp)
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
    @Composable
    private fun AggressiveRadarOverlay(
        distanceMeters: Int,
        speedLimit: Int,
        currentSpeed: Int,
        radarType: String,
        isDarkMode: Boolean,
        source: String? = null,
        onBlacklistClick: () -> Unit
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

                    // Transparência de Fonte (Fase 9)
                    Text(
                        text = "Fonte: ${source ?: "Desconhecida"}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal
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

                // Botão Gigante de Blacklist (Fase 9.1: 72.dp)
                Surface(
                    onClick = onBlacklistClick,
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.4f),
                    modifier = Modifier.size(72.dp)
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
            cachedRadars = radarDao.getRadarsWithinRadius(
                latitude = location.latitude,
                longitude = location.longitude,
                radiusInMeters = CACHE_RADIUS_METERS
            )
            lastQueryLocation = Location(location)
            Log.d(TAG, "Cache espacial atualizado com ${cachedRadars.size} radares.")
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

        // Fase 8: Lê as 4 distâncias customizadas do PreferencesManager
        val distLowSpeed = preferencesManager.distLowSpeed
        val distCity = preferencesManager.distCity
        val distExpress = preferencesManager.distExpress
        val distHighway = preferencesManager.distHighway

        // Fase 9: Filtro Anti-Falsos Positivos (OSM e Blacklist)
        val blockOsm = preferencesManager.blockOsmRadars
        val blacklistedIds = preferencesManager.blacklistedRadarIds

        _currentLocation.value = location
        _currentSpeedKmh.value = speedKmh

        // Caching Espacial em RAM (Anti-Superaquecimento): itera apenas sobre cachedRadars
        val candidates = getOrUpdateCachedRadars(location)

        val radarLocation = Location("radar_candidate")
        val matchingRadars = mutableListOf<NearbyRadar>()
        var shouldTriggerSound = false

        for (radar in candidates) {
            // Fase 9: Primeira regra de filtro - o app deve tratar radares banidos como se fossem invisíveis
            if (preferencesManager.isRadarBanned(radar.id)) {
                continue
            }

            if (blockOsm && radar.source?.equals("OSM", ignoreCase = true) == true) {
                continue
            }

            radarLocation.latitude = radar.latitude
            radarLocation.longitude = radar.longitude

            val distanceMeters = location.distanceTo(radarLocation)

            // Define o alertDistance avaliando a propriedade radar.speedLimit (Fase 8)
            val alertDistance = getAlertDistanceForRadar(
                speedLimit = radar.speedLimit,
                distLowSpeed = distLowSpeed,
                distCity = distCity,
                distExpress = distExpress,
                distHighway = distHighway
            )

            // Filtro de proximidade inicial flexível conforme Fase 8
            if (distanceMeters <= alertDistance) {
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
                        matchingRadars.add(
                            NearbyRadar(
                                radar = radar,
                                distanceMeters = distanceMeters,
                                alertDistanceMeters = alertDistance
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
                                    Log.d(TAG, "ALERTA SONORO: Radar ID ${radar.id} (${radar.speedLimit} km/h) a ${distanceMeters.toInt()}m. Vel: ${speedKmh.toInt()} km/h")
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
        _closestRadarAlertDistance.value = closest?.alertDistanceMeters ?: distHighway.toFloat()

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
        customDistLowSpeed: Int? = null,
        customDistCity: Int? = null,
        customDistExpress: Int? = null,
        customDistHighway: Int? = null,
        customBlockOsm: Boolean? = null,
        customBlacklistedIds: Set<String>? = null
    ): List<NearbyRadar> = withContext(Dispatchers.IO) {
        val distLowSpeed = customDistLowSpeed ?: if (::preferencesManager.isInitialized) preferencesManager.distLowSpeed else PreferencesManager.DEFAULT_DIST_LOW_SPEED
        val distCity = customDistCity ?: if (::preferencesManager.isInitialized) preferencesManager.distCity else PreferencesManager.DEFAULT_DIST_CITY
        val distExpress = customDistExpress ?: if (::preferencesManager.isInitialized) preferencesManager.distExpress else PreferencesManager.DEFAULT_DIST_EXPRESS
        val distHighway = customDistHighway ?: if (::preferencesManager.isInitialized) preferencesManager.distHighway else PreferencesManager.DEFAULT_DIST_HIGHWAY

        val blockOsm = customBlockOsm ?: if (::preferencesManager.isInitialized) preferencesManager.blockOsmRadars else true
        val blacklistedIds = customBlacklistedIds ?: if (::preferencesManager.isInitialized) preferencesManager.blacklistedRadarIds else emptySet()

        // Caching Espacial em RAM (Anti-Superaquecimento): itera apenas sobre cachedRadars
        val candidates = getOrUpdateCachedRadars(location)

        val radarLocation = Location("radar_check")
        val matchingRadars = mutableListOf<NearbyRadar>()

        val effectiveVehicleBearing = lastValidBearing ?: if (location.hasBearing() && location.bearing != 0.0f) location.bearing else null

        for (radar in candidates) {
            // Fase 9: Primeira regra de filtro - o app deve tratar radares banidos como se fossem invisíveis
            val isBanned = if (customBlacklistedIds != null) {
                customBlacklistedIds.contains(radar.id.toString())
            } else if (::preferencesManager.isInitialized) {
                preferencesManager.isRadarBanned(radar.id)
            } else {
                false
            }
            if (isBanned) {
                continue
            }

            if (blockOsm && radar.source?.equals("OSM", ignoreCase = true) == true) {
                continue
            }

            radarLocation.latitude = radar.latitude
            radarLocation.longitude = radar.longitude

            val distanceMeters = location.distanceTo(radarLocation)

            val alertDistance = getAlertDistanceForRadar(
                speedLimit = radar.speedLimit,
                distLowSpeed = distLowSpeed,
                distCity = distCity,
                distExpress = distExpress,
                distHighway = distHighway
            )

            if (distanceMeters <= alertDistance) {
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
                        matchingRadars.add(
                            NearbyRadar(
                                radar = radar,
                                distanceMeters = distanceMeters,
                                alertDistanceMeters = alertDistance
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
