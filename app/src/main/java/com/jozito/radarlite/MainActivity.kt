package com.jozito.radarlite

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jozito.radarlite.data.PreferencesManager
import com.jozito.radarlite.data.RadarDao
import com.jozito.radarlite.data.RadarDatabase
import com.jozito.radarlite.data.RadarEntity
import com.jozito.radarlite.data.RadarSyncManager
import com.jozito.radarlite.service.RadarOverlayService
import com.jozito.radarlite.ui.theme.RadarLiteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadarLiteTheme {
                RadarLiteApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarLiteApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { PreferencesManager.getInstance(context) }
    val radarDao: RadarDao = remember { RadarDatabase.getDatabase(context).radarDao() }
    val coroutineScope = rememberCoroutineScope()
    var showBlacklistDialog by remember { mutableStateOf(false) }

    // Estados de sincronização com o GitHub (Safe Swap)
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusText by remember { mutableStateOf("") }
    var showSyncSuccessDialog by remember { mutableStateOf(false) }
    var showSyncErrorDialog by remember { mutableStateOf(false) }
    var syncSavedCount by remember { mutableIntStateOf(0) }
    var syncErrorMessage by remember { mutableStateOf<String?>(null) }

    // Estados reativos do serviço
    val isRunning by RadarOverlayService.isRunning.collectAsState()
    val currentSpeed by RadarOverlayService.currentSpeedKmh.collectAsState()
    val closestRadar by RadarOverlayService.closestRadar.collectAsState()
    val isGpsLost by RadarOverlayService.isGpsLost.collectAsState()

    // Preferência do usuário persistida no SharedPreferences
    var warnOnlyOverSpeed by remember { mutableStateOf(prefs.warnOnlyOverSpeed) }
    var isSoundEnabled by remember { mutableStateOf(prefs.isSoundEnabled) }
    var isNightModeEnabled by remember { mutableStateOf(prefs.isNightModeEnabled) }
    var isHudModeEnabled by remember { mutableStateOf(prefs.isHudModeEnabled) }
    var isBluetoothAutostartEnabled by remember { mutableStateOf(prefs.isBluetoothAutostartEnabled) }
    var selectedBluetoothDeviceAddress by remember { mutableStateOf(prefs.selectedBluetoothDeviceAddress) }
    var selectedBluetoothDeviceName by remember { mutableStateOf(prefs.selectedBluetoothDeviceName) }
    var showBluetoothDeviceDialog by remember { mutableStateOf(false) }
    var lastSyncTimestamp by remember { mutableStateOf(prefs.lastSyncTimestamp) }
    var lastSyncCount by remember { mutableIntStateOf(prefs.lastSyncCount) }

    // Fase 8: Distâncias customizáveis por limite de velocidade da via
    var distLowSpeed by remember { mutableIntStateOf(prefs.distLowSpeed) }
    var distCity by remember { mutableIntStateOf(prefs.distCity) }
    var distExpress by remember { mutableIntStateOf(prefs.distExpress) }
    var distHighway by remember { mutableIntStateOf(prefs.distHighway) }

    // Fase 9: Filtro Anti-Falsos Positivos (OSM e Blacklist)
    var blockOsmRadars by remember { mutableStateOf(prefs.blockOsmRadars) }
    var blacklistedCount by remember { mutableIntStateOf(prefs.blacklistedRadarIds.size) }

    // Estados de permissão
    var hasBluetoothPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasBackgroundLocation by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    // Atualiza o estado das permissões quando o app ganha foco (ex: voltando das Configurações)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasFineLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }

                hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                blacklistedCount = prefs.blacklistedRadarIds.size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Launcher para permissão de Bluetooth Connect (Android 12+)
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBluetoothPermission = granted
        if (granted) {
            showBluetoothDeviceDialog = true
        }
    }

    // Launcher para permissões de primeiro plano (Localização e Notificação)
    val foregroundPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasFineLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Launcher para permissão de segundo plano (Android 10+)
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBackgroundLocation = granted
    }

    // Função auxiliar para solicitar permissões
    fun requestCriticalPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        foregroundPermissionsLauncher.launch(permissions.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "RadarLite",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Cartão de Status do Serviço
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (!isRunning) {
                                "Monitoramento Parado"
                            } else if (isGpsLost) {
                                "Sinal GPS Perdido"
                            } else {
                                "Monitoramento Ativo"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (!isRunning) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else if (isGpsLost) {
                                Color(0xFFE65100)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (!isRunning) {
                                "Pronto para iniciar"
                            } else if (isGpsLost) {
                                "Buscando sinal GPS (túnel ou zona morta)..."
                            } else {
                                "Velocidade: ${currentSpeed.toInt()} km/h"
                            },
                            fontSize = 14.sp,
                            color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (!isRunning) Color(0xFF9E9E9E)
                                else if (isGpsLost) Color(0xFFFF9800)
                                else Color(0xFF4CAF50)
                            )
                    )
                }
            }

            // Radar Próximo (se houver alerta ativo e sinal GPS estiver válido)
            AnimatedVisibility(visible = isRunning && !isGpsLost && closestRadar != null) {
                closestRadar?.let { nearby ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                val (typeIcon, typeLabel) = RadarOverlayService.getRadarTypeInfo(nearby.radar.type)
                                Text(
                                    text = "Radar a ${nearby.distanceMeters.toInt()}m",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$typeIcon $typeLabel",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${nearby.radar.speedLimit}",
                                        color = MaterialTheme.colorScheme.onError,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // BOTÃO GRANDE CENTRALIZADO: Iniciar ou Parar Monitoramento
            Button(
                onClick = {
                    if (isRunning) {
                        // Envia Intent para parar o RadarOverlayService
                        val stopIntent = Intent(context, RadarOverlayService::class.java).apply {
                            action = RadarOverlayService.ACTION_STOP
                        }
                        context.stopService(stopIntent)
                        Toast.makeText(context, "Monitoramento finalizado", Toast.LENGTH_SHORT).show()
                    } else {
                        // 1. Verifica se possui permissão de localização precisa
                        if (!hasFineLocation) {
                            requestCriticalPermissions()
                            Toast.makeText(context, "Conceda permissão de localização para continuar", Toast.LENGTH_LONG).show()
                            return@Button
                        }

                        // 2. Verifica se a permissão de sobreposição (Janela Flutuante) está concedida
                        val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(context)
                        } else {
                            true
                        }

                        // Só permite iniciar o serviço se canDrawOverlays for verdadeiro
                        if (!canDrawOverlays) {
                            Toast.makeText(
                                context,
                                "Ative a permissão de sobreposição para a Janela Flutuante",
                                Toast.LENGTH_LONG
                            ).show()

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val overlayIntent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(overlayIntent)
                            }
                            return@Button
                        }

                        // 3. Envia Intent para iniciar o RadarOverlayService usando ContextCompat.startForegroundService
                        val startIntent = Intent(context, RadarOverlayService::class.java).apply {
                            action = RadarOverlayService.ACTION_START
                        }
                        ContextCompat.startForegroundService(context, startIntent)
                        Toast.makeText(context, "Monitoramento iniciado", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = if (isRunning) "Parar Monitoramento" else "Iniciar Monitoramento",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // AUTOMAÇÃO VEICULAR (BLUETOOTH AUTO-START)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "🚗", fontSize = 20.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Automação Veicular (Bluetooth)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Liga e desliga o radar automaticamente com o carro",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch principal de ativação
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Iniciar com Bluetooth do Carro",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Abre a janela flutuante ao conectar na multimídia e encerra ao desligar a ignição.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = isBluetoothAutostartEnabled,
                            onCheckedChange = { isChecked ->
                                isBluetoothAutostartEnabled = isChecked
                                prefs.isBluetoothAutostartEnabled = isChecked
                                if (isChecked && selectedBluetoothDeviceAddress == null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        showBluetoothDeviceDialog = true
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    if (isBluetoothAutostartEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // Card com o dispositivo configurado
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedBluetoothDeviceAddress != null) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selectedBluetoothDeviceAddress != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                if (selectedBluetoothDeviceAddress != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = "📻", fontSize = 16.sp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = selectedBluetoothDeviceName ?: "Dispositivo Selecionado",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            Text(
                                                text = selectedBluetoothDeviceAddress ?: "",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "🟢 Multimídia autorizada a ligar o serviço",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                                                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                                } else {
                                                    showBluetoothDeviceDialog = true
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Trocar", fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "⚠️ Nenhuma multimídia específica selecionada",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Escolha o som ou multimídia do seu carro para impedir que fones de ouvido ou smartwatches ativem o radar por engano fora do carro.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                                                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                            } else {
                                                showBluetoothDeviceDialog = true
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Escolher Dispositivo do Carro", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // PREFERÊNCIAS DE ÁUDIO E VELOCIDADE
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Switch 1: Ativar Alerta Sonoro
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Ativar Alerta Sonoro",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Desative para manter apenas o aviso visual na tela",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = isSoundEnabled,
                            onCheckedChange = { isChecked ->
                                isSoundEnabled = isChecked
                                prefs.isSoundEnabled = isChecked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch 2: Avisar apenas se estiver acima da velocidade do radar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Avisar apenas se estiver acima da velocidade do radar",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (warnOnlyOverSpeed) {
                                    "O bipe só tocará se a sua velocidade ultrapassar o limite do radar."
                                } else {
                                    "O bipe tocará ao atingir 90% da velocidade máxima permitida."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = warnOnlyOverSpeed,
                            onCheckedChange = { isChecked ->
                                warnOnlyOverSpeed = isChecked
                                prefs.warnOnlyOverSpeed = isChecked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch 3: Modo Noturno (Fundo Escuro)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Modo Noturno (Fundo Escuro)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Fundo preto absoluto (#000000) e alto contraste. Não ofusca a visão à noite. Visão direta na multimídia ou suporte (sem espelhar).",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = isNightModeEnabled,
                            onCheckedChange = { isChecked ->
                                isNightModeEnabled = isChecked
                                prefs.isNightModeEnabled = isChecked
                                RadarOverlayService.setNightMode(isChecked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch 4: Modo HUD / Espelhado (Para-brisa)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Modo HUD / Espelhado (Para-brisa)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Inverte a janela horizontalmente. Use APENAS com o celular deitado no painel sob o vidro para ver a reflexão correta no para-brisa.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = isHudModeEnabled,
                            onCheckedChange = { isChecked ->
                                isHudModeEnabled = isChecked
                                prefs.isHudModeEnabled = isChecked
                                RadarOverlayService.setHudMode(isChecked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch 5: Ignorar Radares do OSM (Fase 9)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Ignorar Radares do OSM (OpenStreetMap)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Remove radares não-oficiais para evitar falsos positivos",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }

                        Switch(
                            checked = blockOsmRadars,
                            onCheckedChange = { isChecked ->
                                blockOsmRadars = isChecked
                                prefs.blockOsmRadars = isChecked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }

                    // Seção da Blacklist Local (Fase 9 / 9.1)
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Radares Banidos (Blacklist)",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (blacklistedCount > 0)
                                        "$blacklistedCount radar(es) ignorado(s) pelo botão '✕'"
                                    else
                                        "Nenhum radar ignorado no momento",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                            if (blacklistedCount > 0) {
                                TextButton(
                                    onClick = {
                                        prefs.clearBlacklistedRadars()
                                        blacklistedCount = 0
                                        Toast.makeText(context, "Blacklist limpa com sucesso!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Limpar", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { showBlacklistDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Gerenciar Radares Ignorados",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CONFIGURAÇÃO DE DISTÂNCIAS DE ALERTA (Fase 8)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "📏", fontSize = 20.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Distâncias de Alerta por Via",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Ajuste a antecedência do aviso para evitar frenagens bruscas",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 1. Zonas de 40 km/h (dist_low_speed)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Zonas de 40 km/h: ${distLowSpeed}m",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Até 40 km/h",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = distLowSpeed.toFloat(),
                        onValueChange = { newVal ->
                            val snapped = ((newVal / 10f).roundToInt() * 10).coerceIn(80, 400)
                            distLowSpeed = snapped
                            prefs.distLowSpeed = snapped
                        },
                        valueRange = 80f..400f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Vias Urbanas (dist_city: 41 a 60 km/h)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Zonas de 41 a 60 km/h: ${distCity}m",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "41 a 60 km/h",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = distCity.toFloat(),
                        onValueChange = { newVal ->
                            val snapped = ((newVal / 10f).roundToInt() * 10).coerceIn(100, 500)
                            distCity = snapped
                            prefs.distCity = snapped
                        },
                        valueRange = 100f..500f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Vias Expressas (dist_express: 61 a 90 km/h)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vias Expressas: ${distExpress}m",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "61 a 90 km/h",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = distExpress.toFloat(),
                        onValueChange = { newVal ->
                            val snapped = ((newVal / 10f).roundToInt() * 10).coerceIn(150, 700)
                            distExpress = snapped
                            prefs.distExpress = snapped
                        },
                        valueRange = 150f..700f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Rodovias (dist_highway: acima de 90 km/h)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rodovias: ${distHighway}m",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Acima de 90 km/h",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = distHighway.toFloat(),
                        onValueChange = { newVal ->
                            val snapped = ((newVal / 10f).roundToInt() * 10).coerceIn(200, 1000)
                            distHighway = snapped
                            prefs.distHighway = snapped
                        },
                        valueRange = 200f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Informação visual sobre o alerta em 2 etapas e botão de redefinir padrões
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Alerta estático nos primeiros 50% da distância e agressivo/piscante nos últimos 50%.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )

                        TextButton(
                            onClick = {
                                distLowSpeed = PreferencesManager.DEFAULT_DIST_LOW_SPEED
                                distCity = PreferencesManager.DEFAULT_DIST_CITY
                                distExpress = PreferencesManager.DEFAULT_DIST_EXPRESS
                                distHighway = PreferencesManager.DEFAULT_DIST_HIGHWAY
                                prefs.distLowSpeed = distLowSpeed
                                prefs.distCity = distCity
                                prefs.distExpress = distExpress
                                prefs.distHighway = distHighway
                            }
                        ) {
                            Text("Padrão", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ATUALIZAÇÃO AUTOMÁTICA VIA GITHUB (Pardal)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            if (!isSyncing) {
                                coroutineScope.launch {
                                    isSyncing = true
                                    syncStatusText = "Conectando ao GitHub..."
                                    val db = RadarDatabase.getDatabase(context)
                                    val syncManager = RadarSyncManager(context, db.radarDao())
                                    val result = syncManager.syncRadars { state, count ->
                                        syncStatusText = "Baixando estado $state ($count radares)..."
                                    }
                                    isSyncing = false
                                    if (result.isSuccess) {
                                        syncSavedCount = result.totalRadarsSaved
                                        lastSyncTimestamp = prefs.lastSyncTimestamp
                                        lastSyncCount = prefs.lastSyncCount
                                        showSyncSuccessDialog = true
                                    } else {
                                        syncErrorMessage = result.errorMessage
                                        showSyncErrorDialog = true
                                    }
                                }
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onSecondary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Atualizando...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Atualizar Base de Radares",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isSyncing) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = syncStatusText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (lastSyncTimestamp > 0L) {
                            val formattedDate = remember(lastSyncTimestamp) {
                                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                sdf.format(Date(lastSyncTimestamp))
                            }
                            val daysSinceSync = remember(lastSyncTimestamp) {
                                ((System.currentTimeMillis() - lastSyncTimestamp) / (1000L * 60 * 60 * 24)).coerceAtLeast(0)
                            }

                            Text(
                                text = "Base atualizada em: $formattedDate ($lastSyncCount radares salvos)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            if (daysSinceSync >= 30) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "⚠️ Base desatualizada há $daysSinceSync dias. Recomenda-se atualizar via internet.",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Text(
                                text = "Base de fábrica (PR, SC, SP e MS embutidos)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Seção de Gerenciamento de Permissões Críticas
            Text(
                text = "Permissões Críticas do Sistema",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Permissão de Localização Precisa
            PermissionStatusRow(
                title = "Localização Precisa (GPS)",
                isGranted = hasFineLocation,
                onRequest = { requestCriticalPermissions() }
            )

            // Permissão de Notificação (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionStatusRow(
                    title = "Notificação em 1º Plano",
                    isGranted = hasNotificationPermission,
                    onRequest = { requestCriticalPermissions() }
                )
            }

            // Permissão de Localização em Segundo Plano (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PermissionStatusRow(
                    title = "Localização em Segundo Plano",
                    isGranted = hasBackgroundLocation,
                    onRequest = {
                        if (hasFineLocation) {
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            requestCriticalPermissions()
                        }
                    }
                )
            }

            // Permissão de Sobreposição (Janela Flutuante)
            PermissionStatusRow(
                title = "Janela Flutuante (Sobreposição)",
                isGranted = hasOverlayPermission,
                onRequest = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                }
            )
            // Permissão de Dispositivos Próximos / Bluetooth do Carro (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionStatusRow(
                    title = "Dispositivos Próximos (Bluetooth)",
                    isGranted = hasBluetoothPermission,
                    onRequest = {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                )
            }
        }
    }

    // Diálogo Seletor de Dispositivo Bluetooth do Veículo
    if (showBluetoothDeviceDialog) {
        val pairedDevices = remember { getPairedBluetoothDevices(context) }
        AlertDialog(
            onDismissRequest = { showBluetoothDeviceDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🚗", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Dispositivo do Veículo", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Selecione o som ou multimídia do seu carro para acordar e manter o serviço ativo:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (pairedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Nenhum dispositivo Bluetooth pareado encontrado.\n\nVerifique se o Bluetooth do smartphone está ligado e se a multimídia do carro já está pareada nas Configurações do Android.",
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                            // Opção Genérica: Qualquer dispositivo Bluetooth
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedBluetoothDeviceAddress = null
                                            selectedBluetoothDeviceName = "Qualquer Dispositivo Bluetooth"
                                            prefs.selectedBluetoothDeviceAddress = null
                                            prefs.selectedBluetoothDeviceName = "Qualquer Dispositivo Bluetooth"
                                            isBluetoothAutostartEnabled = true
                                            prefs.isBluetoothAutostartEnabled = true
                                            showBluetoothDeviceDialog = false
                                        },
                                    color = Color.Transparent
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedBluetoothDeviceAddress == null,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "🌐 Qualquer Dispositivo Bluetooth",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "Inicia com qualquer conexão Bluetooth (sem filtro de carro)",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }

                            // Dispositivos pareados
                            items(pairedDevices) { (name, address) ->
                                val isSelected = selectedBluetoothDeviceAddress.equals(address, ignoreCase = true)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedBluetoothDeviceAddress = address
                                            selectedBluetoothDeviceName = name
                                            prefs.selectedBluetoothDeviceAddress = address
                                            prefs.selectedBluetoothDeviceName = name
                                            isBluetoothAutostartEnabled = true
                                            prefs.isBluetoothAutostartEnabled = true
                                            showBluetoothDeviceDialog = false
                                        },
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = address,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBluetoothDeviceDialog = false }) {
                    Text("Fechar")
                }
            }
        )
    }

    // Alerta de Sucesso da Atualização dos Radares
    if (showSyncSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSyncSuccessDialog = false },
            title = {
                Text(
                    text = "Atualização Concluída",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "A base de radares foi atualizada com sucesso!\n\nForam salvos $syncSavedCount radares consolidados dos estados PR, SC, SP e MS."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showSyncSuccessDialog = false }
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Alerta de Falha da Atualização dos Radares (Safe Swap)
    if (showSyncErrorDialog) {
        AlertDialog(
            onDismissRequest = { showSyncErrorDialog = false },
            title = {
                Text(
                    text = "Falha na Atualização",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Não foi possível concluir a atualização dos radares:\n\n${syncErrorMessage ?: "Erro de conexão com o GitHub."}\n\nO Safe Swap protegeu o banco e a base anterior permanece 100% intacta."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showSyncErrorDialog = false }
                ) {
                    Text("Entendido", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Tela de Gestão de Radares Banidos (Fase 9.1)
    if (showBlacklistDialog) {
        BlacklistManagerDialog(
            onDismissRequest = { showBlacklistDialog = false },
            prefs = prefs,
            radarDao = radarDao,
            onBlacklistUpdated = {
                blacklistedCount = prefs.blacklistedRadarIds.size
            }
        )
    }
}

/**
 * Diálogo de Gestão da Blacklist (Fase 9.1).
 * Carrega a lista completa de radares ignorados pelo usuário a partir do Room DAO,
 * exibindo fonte, tipo e limite de velocidade, com botões para restaurar individualmente
 * ou limpar toda a blacklist.
 */
@Composable
fun BlacklistManagerDialog(
    onDismissRequest: () -> Unit,
    prefs: PreferencesManager,
    radarDao: RadarDao,
    onBlacklistUpdated: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var bannedRadars by remember { mutableStateOf<List<RadarEntity>>(emptyList()) }
    var rawIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    fun loadRadars() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            val ids = prefs.getBlacklistedRadarLongIds()
            val list = if (ids.isNotEmpty()) radarDao.getRadarsByIds(ids) else emptyList()
            withContext(Dispatchers.Main) {
                rawIds = ids
                bannedRadars = list
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRadars()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Radares Ignorados",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (rawIds.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            prefs.clearBlacklistedRadars()
                            onBlacklistUpdated()
                            loadRadars()
                            Toast.makeText(context, "Todos os radares foram restaurados", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(
                            text = "Restaurar Todos",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (rawIds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhum radar na blacklist.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        text = "${rawIds.size} radar(es) na blacklist:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Itens encontrados no banco de dados
                        items(bannedRadars, key = { it.id }) { radar ->
                            val sourceLabel = radar.source?.ifBlank { "OSM" } ?: "OSM"
                            val (_, typeLabel) = RadarOverlayService.getRadarTypeInfo(radar.type)
                            val displayTitle = "$sourceLabel - $typeLabel ${radar.speedLimit} km/h"

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = displayTitle,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "ID: ${radar.id} • Lat: ${String.format(Locale.US, "%.4f", radar.latitude)}, Lng: ${String.format(Locale.US, "%.4f", radar.longitude)}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            prefs.unbanRadar(radar.id)
                                            onBlacklistUpdated()
                                            loadRadars()
                                            Toast.makeText(context, "Radar restaurado", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(text = "Restaurar", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // IDs que podem não existir mais no banco local (caso o banco tenha sido resetado)
                        val foundIds = bannedRadars.map { it.id }.toSet()
                        val missingIds = rawIds.filter { it !in foundIds }
                        items(missingIds, key = { it }) { missingId ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "Radar #$missingId",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Registro removido da base de dados",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            prefs.unbanRadar(missingId)
                                            onBlacklistUpdated()
                                            loadRadars()
                                            Toast.makeText(context, "Radar restaurado", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(text = "Restaurar", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Fechar", fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Obtém a lista de dispositivos Bluetooth já pareados com o smartphone.
 */
fun getPairedBluetoothDevices(context: Context): List<Pair<String, String>> {
    val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    if (!hasPerm) return emptyList()

    return try {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            emptyList()
        } else {
            adapter.bondedDevices?.map { device ->
                val name = try { device.name } catch (e: SecurityException) { null } ?: "Dispositivo Bluetooth"
                val address = device.address
                name to address
            }?.sortedBy { it.first } ?: emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun PermissionStatusRow(
    title: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isGranted) "Concedida" else "Pendente",
                    fontSize = 12.sp,
                    color = if (isGranted) Color(0xFF388E3C) else Color(0xFFD32F2F),
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!isGranted) {
                OutlinedButton(
                    onClick = onRequest,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = "Permitir", fontSize = 12.sp)
                }
            }
        }
    }
}