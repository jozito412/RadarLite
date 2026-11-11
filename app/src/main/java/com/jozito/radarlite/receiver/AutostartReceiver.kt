package com.jozito.radarlite.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jozito.radarlite.InvisibleStartActivity
import com.jozito.radarlite.data.PreferencesManager
import com.jozito.radarlite.service.RadarOverlayService

/**
 * Receiver responsável por inicializar o serviço de rastreamento de radares de forma automática
 * ao ligar o veículo (Boot / QuickBoot) ou quando a central multimídia específica conectar via Bluetooth (ACL ou A2DP/HFP).
 */
class AutostartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutostartReceiver"

        // Ações customizadas para controle remoto por launchers ou apps externos (ex: CoolantHUD)
        const val ACTION_START = "com.jozito412.radarlite.ACTION_START"
        const val ACTION_START_ALT = "com.jozito.radarlite.ACTION_START"
        const val ACTION_STOP = "com.jozito412.radarlite.ACTION_STOP"
        const val ACTION_STOP_ALT = "com.jozito.radarlite.ACTION_STOP"
        const val ACTION_REQUEST_STATE = "com.jozito412.radarlite.ACTION_REQUEST_STATE"
        const val ACTION_REQUEST_STATE_ALT = "com.jozito.radarlite.ACTION_REQUEST_STATE"
        const val ACTION_WATCHDOG_WAKE = "com.jozito412.radarlite.ACTION_WATCHDOG_WAKE"
        const val ACTION_WATCHDOG_WAKE_ALT = "com.jozito.radarlite.ACTION_WATCHDOG_WAKE"
        const val ACTION_STATE_CHANGED = RadarOverlayService.ACTION_STATE_CHANGED
        const val ACTION_STATE_CHANGED_ALT = RadarOverlayService.ACTION_STATE_CHANGED_ALT
        const val EXTRA_IS_RUNNING = RadarOverlayService.EXTRA_IS_RUNNING
        const val ACTION_CUSTOM_START = ACTION_START
        const val ACTION_CUSTOM_STOP = ACTION_STOP

        // Objeto de trava de concorrência para múltiplos disparos simultâneos (ex: múltiplos dispositivos Bluetooth)
        private val startLock = Any()

        // Ações adicionais comuns em centrais multimídia automotivas Android ao ligar a ignição
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"

        /**
         * Cria a Intent explícita direcionada ao RadarOverlayService.
         */
        fun createServiceIntent(context: Context): Intent {
            return Intent(context, RadarOverlayService::class.java).apply {
                action = RadarOverlayService.ACTION_START
            }
        }

        /**
         * Verifica se a ação recebida representa redundância de ignição da central multimídia (Boot / QuickBoot / Screen On / Power Connected).
         * Muitas centrais multimídia automotivas acendem a tela (SCREEN_ON) ou recebem sinal de energia ACC (ACTION_POWER_CONNECTED) ao girar a chave sem reiniciar o Android.
         */
        fun isIgnitionOrBootAction(action: String?): Boolean {
            return action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_SCREEN_ON ||
                    action == Intent.ACTION_POWER_CONNECTED ||
                    action == ACTION_QUICKBOOT_POWERON ||
                    action == ACTION_HTC_QUICKBOOT_POWERON ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED
        }

        /**
         * Verifica se a ação recebida é um gatilho válido de autostart.
         */
        fun isAutostartAction(action: String?): Boolean {
            return isIgnitionOrBootAction(action) ||
                    action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    action == ACTION_START ||
                    action == ACTION_START_ALT ||
                    action == ACTION_WATCHDOG_WAKE ||
                    action == ACTION_WATCHDOG_WAKE_ALT
        }

        /**
         * Determina se a ação e estado do perfil Bluetooth representam conexão veicular (A2DP multimídia ou Headset).
         */
        fun isAutostartBluetoothState(action: String?, state: Int): Boolean {
            if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                return state == BluetoothProfile.STATE_CONNECTED
            }
            return isAutostartAction(action)
        }

        /**
         * Determina se a ação e estado do perfil Bluetooth representam desconexão veicular.
         */
        fun isAutostopBluetoothState(action: String?, state: Int): Boolean {
            if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                return state == BluetoothProfile.STATE_DISCONNECTED
            }
            return isAutostopAction(action)
        }

        /**
         * Verifica se a Intent recebida representa uma conexão (ACL ou perfis de áudio automotivo A2DP/Headset).
         * Multimídias automotivas frequentemente restabelecem conexão pelo perfil de áudio A2DP ao religar.
         */
        fun isAutostartIntent(intent: Intent?): Boolean {
            val action = intent?.action ?: return false
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            return isAutostartBluetoothState(action, state)
        }

        /**
         * Trava lógica: Determina se o serviço deve ser iniciado para a ação recebida,
         * prevenindo disparos concorrentes ou redundantes se o serviço já estiver rodando.
         */
        fun shouldStartService(action: String?, isServiceRunning: Boolean): Boolean {
            return isAutostartAction(action) && !isServiceRunning
        }

        /**
         * Trava lógica baseada na Intent completa.
         */
        fun shouldStartServiceForIntent(intent: Intent?, isServiceRunning: Boolean): Boolean {
            return isAutostartIntent(intent) && !isServiceRunning
        }

        /**
         * Verifica se a ação recebida é um gatilho de desligamento automático (Auto-Stop).
         * Desconectar do som do carro (Bluetooth) ou comando externo customizado encerra o serviço.
         */
        fun isAutostopAction(action: String?): Boolean {
            return action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                    action == ACTION_STOP ||
                    action == ACTION_STOP_ALT
        }

        /**
         * Verifica se a Intent recebida representa desconexão da multimídia (ACL ou perfil de mídia A2DP/Headset).
         */
        fun isAutostopIntent(intent: Intent?): Boolean {
            val action = intent?.action ?: return false
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            return isAutostopBluetoothState(action, state)
        }

        /**
         * Verifica se o dispositivo conectado corresponde ao smartphone do usuário.
         * Na central multimídia automotiva, a multimídia recebe a conexão de smartphones.
         * Não exige restrição de classe AUDIO_VIDEO_CAR_AUDIO.
         * Suporta validação por endereço MAC, Nome do aparelho ou modo genérico.
         */
        fun isTargetBluetoothDevice(
            deviceAddress: String?,
            configuredAddress: String?,
            deviceName: String? = null,
            configuredName: String? = null
        ): Boolean {
            val hasConfiguredAddress = !configuredAddress.isNullOrBlank()
            val hasConfiguredName = !configuredName.isNullOrBlank() && configuredName != "Qualquer Dispositivo Bluetooth"

            // Modo genérico / sem filtro restritivo: aceita qualquer dispositivo pareado conectado
            if (!hasConfiguredAddress && !hasConfiguredName) {
                return true
            }

            // 1. Validação por Endereço MAC (case-insensitive)
            if (hasConfiguredAddress && !deviceAddress.isNullOrBlank()) {
                if (deviceAddress.equals(configuredAddress, ignoreCase = true)) {
                    return true
                }
            }

            // 2. Validação por Nome do Aparelho (flexível, exato ou substring, ex: "S25" vs "Galaxy S25")
            if (hasConfiguredName && !deviceName.isNullOrBlank()) {
                val cleanDevName = deviceName.trim().lowercase()
                val cleanCfgName = configuredName!!.trim().lowercase()
                if (cleanDevName == cleanCfgName || cleanDevName.contains(cleanCfgName) || cleanCfgName.contains(cleanDevName)) {
                    return true
                }
            }

            // 3. Fallback: Se o configuredName foi definido mas deviceName era nulo/inacessível no intent,
            // verifica se o MAC conectado pertence a algum dispositivo pareado que contenha o configuredName
            if (hasConfiguredName && !deviceAddress.isNullOrBlank()) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val bonded = adapter?.bondedDevices
                    val match = bonded?.firstOrNull { it.address.equals(deviceAddress, ignoreCase = true) }
                    val bondedName = match?.name?.trim()?.lowercase()
                    val cleanCfgName = configuredName!!.trim().lowercase()
                    if (bondedName != null && (bondedName == cleanCfgName || bondedName.contains(cleanCfgName) || cleanCfgName.contains(bondedName))) {
                        return true
                    }
                } catch (e: Exception) {
                    // Ignora em caso de falta de permissão ou restrição
                }
            }

            return false
        }

        fun isTargetBluetoothDevice(deviceAddress: String?, configuredAddress: String?): Boolean {
            return isTargetBluetoothDevice(deviceAddress, configuredAddress, null, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Broadcast recebido: $action")

        // 1. Solicitação remota de status (Ping) por Launcher customizado (ex: CoolantHUD)
        if (action == ACTION_REQUEST_STATE || action == ACTION_REQUEST_STATE_ALT) {
            val currentState = RadarOverlayService.isRunning.value
            RadarOverlayService.broadcastState(context, currentState)
            Log.d(TAG, "Solicitação de status ACTION_REQUEST_STATE atendida com rota expressa (Foreground Broadcast): is_running=$currentState.")
            return
        }

        val prefs = PreferencesManager.getInstance(context)

        // 2. Pulso de vida (Watchdog) enviado pelo Launcher (CoolantHUD) ao acender a tela após Deep Sleep
        if (action == ACTION_WATCHDOG_WAKE || action == ACTION_WATCHDOG_WAKE_ALT) {
            if (prefs.isServiceIntentionallyStopped) {
                Log.d(TAG, "Pulso Watchdog recebido ($action), porém o serviço foi desligado intencionalmente pelo usuário (is_service_intentionally_stopped = true). Ignorando.")
                return
            }
            Log.i(TAG, "Pulso Watchdog recebido ($action). Restaurando RadarOverlayService...")
            startServiceSilently(context, action)
            return
        }

        // 3. Comandos remotos explícitos do Launcher CoolantHUD (atualizam a intenção do usuário - Fase 11)
        if (action == ACTION_START || action == ACTION_START_ALT) {
            Log.i(TAG, "Comando remoto ACTION_START interceptado ($action).")

            // Atualiza a flag no PreferencesManager (is_service_intentionally_stopped = false)
            prefs.isServiceIntentionallyStopped = false

            if (RadarOverlayService.isRunning.value) {
                Log.d(TAG, "RadarOverlayService já está em execução. Ignorando chamada duplicada de $action.")
                return
            }

            // Trampoline Activity invisível: Técnica à prova de falhas para contornar restrições de Background Receiver
            try {
                val trampolineIntent = Intent(context, InvisibleStartActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(trampolineIntent)
                Log.d("RadarLite", "InvisibleStartActivity disparada com sucesso a partir de $action")
            } catch (e: Exception) {
                Log.e("RadarLite", "Start block: Falha ao iniciar InvisibleStartActivity, tentando fallback direto", e)
                try {
                    val serviceIntent = createServiceIntent(context)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d("RadarLite", "Fallback direto startForegroundService executado para $action")
                } catch (e2: Exception) {
                    Log.e("RadarLite", "Start block: Fallback direto também falhou", e2)
                }
            }
            return
        }
        if (action == ACTION_STOP || action == ACTION_STOP_ALT) {
            Log.i(TAG, "Comando remoto ACTION_STOP interceptado ($action).")

            // Tarefa 1: Travar o Cão de Guarda - Salva a intenção de parada de forma estritamente síncrona
            prefs.setServiceIntentionallyStoppedSync(true)

            // Só DEPOIS de salvar essa intenção, chama o stopMonitoring() na instância ativa
            RadarOverlayService.activeInstance?.stopMonitoring()

            stopServiceSilently(context, action)
            return
        }

        // 4. Redundância de Ignição / Boot da Central Multimídia (Boot, QuickBoot, Screen On ou Power Connected)
        if (isIgnitionOrBootAction(action)) {
            // Fase 11: Se o usuário parou o serviço intencionalmente (via STOP), não liga sozinho ao religar o carro
            if (prefs.isServiceIntentionallyStopped) {
                Log.d(TAG, "Serviço parado intencionalmente pelo usuário (is_service_intentionally_stopped = true). Ignorando gatilho de ignição/boot $action.")
                return
            }
            if (!prefs.isAutostartEnabled) {
                Log.d(TAG, "Autostart desativado nas preferências (is_autostart_enabled = false). Ignorando evento de ignição/boot $action.")
                return
            }
            Log.i(TAG, "Gatilho de ignição veicular detectado ($action). Inicializando RadarOverlayService...")
            startServiceSilently(context, action)
            return
        }

        // 5. Conexão/Desconexão Bluetooth (Smartphones conectando/desconectando da Central Multimídia)
        val isBtAction = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED

        if (isBtAction) {
            val isConnect = isAutostartIntent(intent)
            val isDisconnect = isAutostopIntent(intent)

            if (!prefs.isAutostartEnabled) {
                Log.d(TAG, "Autostart por Bluetooth desativado nas preferências (is_autostart_enabled = false). Ignorando evento $action.")
                return
            }

            val device: BluetoothDevice? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            val configuredAddress = prefs.selectedBluetoothDeviceAddress
            val configuredName = prefs.selectedBluetoothDeviceName
            var deviceName: String? = null
            try {
                deviceName = device?.name
            } catch (e: SecurityException) {
                Log.w(TAG, "Permissão BLUETOOTH_CONNECT não concedida para ler device.name", e)
            }

            // Se o nome não pôde ser lido diretamente, tenta obter via bondedDevices do BluetoothAdapter
            if (deviceName.isNullOrBlank() && device != null) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val bonded = adapter?.bondedDevices
                    val match = bonded?.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                    if (match != null) {
                        deviceName = match.name
                    }
                } catch (e: Exception) {
                    // Ignora
                }
            }

            if (!isTargetBluetoothDevice(device?.address, configuredAddress, deviceName, configuredName)) {
                Log.d(TAG, "Dispositivo Bluetooth (${device?.address} / $deviceName) ignorado: não corresponde ao configurado ($configuredAddress / $configuredName).")
                return
            }

            if (isConnect) {
                Log.i(TAG, "Smartphone alvo conectado via Bluetooth (${device?.address} / $deviceName). Restaurando intenção e inicializando RadarOverlayService...")
                // Conexão do smartphone pareado representa intenção ativa do usuário: anula qualquer bloqueio anterior
                prefs.isServiceIntentionallyStopped = false
                startServiceSilently(context, action)
                return
            } else if (isDisconnect) {
                Log.i(TAG, "Smartphone alvo desconectado via Bluetooth (${device?.address} / $deviceName). Encerrando RadarOverlayService...")
                stopServiceSilently(context, action)
                return
            }
        }

        if (isAutostartIntent(intent)) {
            if (prefs.isServiceIntentionallyStopped) {
                Log.d(TAG, "Serviço parado intencionalmente pelo usuário. Ignorando autostart intent ($action).")
                return
            }
            startServiceSilently(context, action)
        } else if (isAutostopIntent(intent)) {
            stopServiceSilently(context, action)
        }
    }

    private fun startServiceSilently(context: Context, triggerAction: String) {
        synchronized(startLock) {
            PreferencesManager.getInstance(context).isServiceIntentionallyStopped = false
            if (RadarOverlayService.isRunning.value) {
                Log.d(TAG, "RadarOverlayService já está em execução. Ignorando gatilho duplicado ($triggerAction).")
                return
            }

            Log.i(TAG, "Inicializando RadarOverlayService a partir de $triggerAction (SDK: ${Build.VERSION.SDK_INT})...")

            // 1. Tenta acionar via Trampoline Activity invisível (garante inicialização em primeiro plano no Android 8.0+ / 14+)
            try {
                val trampolineIntent = Intent(context, InvisibleStartActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(trampolineIntent)
                Log.d(TAG, "InvisibleStartActivity disparada com sucesso a partir de $triggerAction")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao iniciar InvisibleStartActivity, tentando fallback direto", e)
            }

            // 2. Fallback direto startForegroundService
            try {
                val serviceIntent = createServiceIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "RadarOverlayService iniciado com sucesso (fallback direto) a partir de $triggerAction.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar RadarOverlayService no fallback direto a partir de $triggerAction", e)
            }
        }
    }

    private fun stopServiceSilently(context: Context, triggerAction: String) {
        Log.i(TAG, "Encerrando RadarOverlayService silenciosamente a partir de $triggerAction...")
        try {
            // 1. Notifica instância ativa em RAM para parada imediata do overlay, sons e GPS
            RadarOverlayService.activeInstance?.stopMonitoring()

            // 2. Força interrupção do serviço via Context.stopService (NUNCA chamar startForegroundService para parar)
            val stopIntent = Intent(context, RadarOverlayService::class.java).apply {
                action = RadarOverlayService.ACTION_STOP
            }
            context.stopService(stopIntent)

            // 3. Emite confirmação de estado desligado para sincronização com Launcher
            RadarOverlayService.broadcastState(context, false)
            Log.d(TAG, "RadarOverlayService encerrado com sucesso a partir de $triggerAction.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao encerrar RadarOverlayService a partir de $triggerAction", e)
        }
    }
}
