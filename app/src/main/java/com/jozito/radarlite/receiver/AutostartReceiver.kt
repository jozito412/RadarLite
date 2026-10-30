package com.jozito.radarlite.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
        const val ACTION_STOP = "com.jozito412.radarlite.ACTION_STOP"
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
         * Verifica se a ação recebida é um gatilho válido de autostart.
         */
        fun isAutostartAction(action: String?): Boolean {
            return action == Intent.ACTION_BOOT_COMPLETED ||
                    action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    action == ACTION_QUICKBOOT_POWERON ||
                    action == ACTION_HTC_QUICKBOOT_POWERON ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    action == ACTION_START
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
                    action == ACTION_STOP
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
         * Verifica se o endereço MAC do dispositivo conectado bate com o dispositivo configurado pelo usuário.
         * Se configuredAddress for nulo ou vazio, aceita qualquer dispositivo pareado (modo genérico).
         */
        fun isTargetBluetoothDevice(deviceAddress: String?, configuredAddress: String?): Boolean {
            if (configuredAddress.isNullOrBlank()) return true
            return deviceAddress != null && deviceAddress.equals(configuredAddress, ignoreCase = true)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Broadcast recebido: $action")

        val prefs = PreferencesManager.getInstance(context)

        // Se for um evento de Bluetooth, valida se a automação está ligada e se bate com o dispositivo do carro
        val isBtAction = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED

        if (isBtAction) {
            if (!prefs.isBluetoothAutostartEnabled) {
                Log.d(TAG, "Autostart por Bluetooth desativado nas configurações. Ignorando evento $action.")
                return
            }

            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            val configuredAddress = prefs.selectedBluetoothDeviceAddress
            if (!isTargetBluetoothDevice(device?.address, configuredAddress)) {
                Log.d(TAG, "Dispositivo Bluetooth (${device?.address}) ignorado: não é o dispositivo do carro configurado ($configuredAddress).")
                return
            }
        }

        if (action == ACTION_START || isAutostartIntent(intent)) {
            // Trava lógica contra concorrência e múltiplos disparos simultâneos
            synchronized(startLock) {
                if (RadarOverlayService.isRunning.value) {
                    Log.d(TAG, "RadarOverlayService já está em execução. Ignorando gatilho de autostart duplicado ($action).")
                    return
                }

                Log.i(TAG, "Gatilho de inicialização detectado ($action). Inicializando RadarOverlayService...")

                val serviceIntent = createServiceIntent(context)

                try {
                    // Inicia o serviço como Foreground Service usando ContextCompat
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "RadarOverlayService iniciado com sucesso a partir de $action.")
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao iniciar RadarOverlayService a partir do broadcast $action", e)
                }
            }
        } else if (action == ACTION_STOP || isAutostopIntent(intent)) {
            Log.i(TAG, "Gatilho de parada detectado ($action). Encerrando RadarOverlayService...")

            try {
                val stopIntent = Intent(context, RadarOverlayService::class.java).apply {
                    setAction(RadarOverlayService.ACTION_STOP)
                }
                context.stopService(stopIntent)
                Log.d(TAG, "RadarOverlayService encerrado com sucesso a partir do broadcast $action.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao encerrar RadarOverlayService a partir do broadcast $action", e)
            }
        }
    }
}
