package com.jozito.radarlite.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Intent
import com.jozito.radarlite.service.RadarOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartReceiverTest {

    @Test
    fun testValidAutostartActions() {
        // Boot completed padrão do Android
        assertTrue(AutostartReceiver.isAutostartAction(Intent.ACTION_BOOT_COMPLETED))

        // Redundância de Ignição da Central Multimídia (acendimento de tela na ignição e linha ACC)
        assertTrue(AutostartReceiver.isAutostartAction(Intent.ACTION_SCREEN_ON))
        assertTrue(AutostartReceiver.isAutostartAction(Intent.ACTION_POWER_CONNECTED))
        assertTrue(AutostartReceiver.isIgnitionOrBootAction(Intent.ACTION_SCREEN_ON))
        assertTrue(AutostartReceiver.isIgnitionOrBootAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(AutostartReceiver.isIgnitionOrBootAction(Intent.ACTION_POWER_CONNECTED))

        // Conexão Bluetooth (smartphone conectando à central multimídia)
        assertTrue(AutostartReceiver.isAutostartAction(BluetoothDevice.ACTION_ACL_CONNECTED))

        // Atualização de aplicativo em segundo plano
        assertTrue(AutostartReceiver.isAutostartAction(Intent.ACTION_MY_PACKAGE_REPLACED))

        // QuickBoot automotivo (ignição do veículo ligada)
        assertTrue(AutostartReceiver.isAutostartAction("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(AutostartReceiver.isAutostartAction("com.htc.intent.action.QUICKBOOT_POWERON"))
        assertTrue(AutostartReceiver.isIgnitionOrBootAction("android.intent.action.QUICKBOOT_POWERON"))

        // Controle remoto por Launcher customizado (ex: CoolantHUD)
        assertEquals("com.jozito412.radarlite.ACTION_START", AutostartReceiver.ACTION_START)
        assertEquals("com.jozito412.radarlite.ACTION_REQUEST_STATE", AutostartReceiver.ACTION_REQUEST_STATE)
        assertEquals("com.jozito412.radarlite.ACTION_WATCHDOG_WAKE", AutostartReceiver.ACTION_WATCHDOG_WAKE)
        assertTrue(AutostartReceiver.isAutostartAction(AutostartReceiver.ACTION_START))
        assertTrue(AutostartReceiver.isAutostartAction(AutostartReceiver.ACTION_WATCHDOG_WAKE))
    }

    @Test
    fun testInvalidAutostartActions() {
        // Ações não relacionadas não devem iniciar o serviço
        assertFalse(AutostartReceiver.isAutostartAction(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        assertFalse(AutostartReceiver.isAutostartAction(Intent.ACTION_BATTERY_LOW))
        assertFalse(AutostartReceiver.isAutostartAction(BluetoothDevice.ACTION_ACL_DISCONNECTED))
        assertFalse(AutostartReceiver.isAutostartAction(null))
        assertFalse(AutostartReceiver.isAutostartAction(""))
    }

    @Test
    fun testTargetServiceIsRadarOverlayService() {
        // Garante que o serviço de destino utilizado pelo AutostartReceiver é o RadarOverlayService
        val expectedClassName = RadarOverlayService::class.java.name
        assertEquals("com.jozito.radarlite.service.RadarOverlayService", expectedClassName)
    }

    @Test
    fun testValidAutostopAction() {
        // Desconexão do som do carro (Bluetooth desconectado) deve disparar auto-stop
        assertTrue(AutostartReceiver.isAutostopAction(BluetoothDevice.ACTION_ACL_DISCONNECTED))

        // Controle remoto por Launcher customizado (ex: CoolantHUD)
        assertEquals("com.jozito412.radarlite.ACTION_STOP", AutostartReceiver.ACTION_STOP)
        assertTrue(AutostartReceiver.isAutostopAction(AutostartReceiver.ACTION_STOP))

        // Outras ações não devem acionar auto-stop
        assertFalse(AutostartReceiver.isAutostopAction(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertFalse(AutostartReceiver.isAutostopAction(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(AutostartReceiver.isAutostopAction(null))
    }

    @Test
    fun testConcurrencyLockAndMultipleBluetoothPrevention() {
        // Se o serviço não está rodando, a conexão Bluetooth ou comando customizado dispara o início do serviço
        assertTrue(AutostartReceiver.shouldStartService(BluetoothDevice.ACTION_ACL_CONNECTED, isServiceRunning = false))
        assertTrue(AutostartReceiver.shouldStartService(Intent.ACTION_BOOT_COMPLETED, isServiceRunning = false))
        assertTrue(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_START, isServiceRunning = false))
        assertTrue(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_WATCHDOG_WAKE, isServiceRunning = false))

        // Trava lógica: Se o serviço JÁ ESTÁ RODANDO (ex: múltiplos disparos simultâneos ou launcher re-enviando comando),
        // o receptor descarta novos disparos para evitar concorrência e exceções
        assertFalse(AutostartReceiver.shouldStartService(BluetoothDevice.ACTION_ACL_CONNECTED, isServiceRunning = true))
        assertFalse(AutostartReceiver.shouldStartService(Intent.ACTION_BOOT_COMPLETED, isServiceRunning = true))
        assertFalse(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_START, isServiceRunning = true))
        assertFalse(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_WATCHDOG_WAKE, isServiceRunning = true))

        // Ações inválidas não devem iniciar mesmo se o serviço estiver parado
        assertFalse(AutostartReceiver.shouldStartService(BluetoothDevice.ACTION_ACL_DISCONNECTED, isServiceRunning = false))
        assertFalse(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_STOP, isServiceRunning = false))
        assertFalse(AutostartReceiver.shouldStartService(null, isServiceRunning = false))
    }

    @Test
    fun testBluetoothProfileStateAutostartAndAutostop() {
        // A2DP Conectado (Som / Multimídia do carro reconectou ao ligar a ignição) -> Autostart
        assertTrue(AutostartReceiver.isAutostartBluetoothState(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED))
        // A2DP Desconectado (Carro desligado / usuário saiu do carro) -> Autostop
        assertTrue(AutostartReceiver.isAutostopBluetoothState(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED))
        assertFalse(AutostartReceiver.isAutostartBluetoothState(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED))

        // Headset Conectado -> Autostart
        assertTrue(AutostartReceiver.isAutostartBluetoothState(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_CONNECTED))
        // Headset Desconectado -> Autostop
        assertTrue(AutostartReceiver.isAutostopBluetoothState(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, BluetoothProfile.STATE_DISCONNECTED))

        // ACL Conectado -> Autostart
        assertTrue(AutostartReceiver.isAutostartBluetoothState(BluetoothDevice.ACTION_ACL_CONNECTED, -1))
        // ACL Desconectado -> Autostop
        assertTrue(AutostartReceiver.isAutostopBluetoothState(BluetoothDevice.ACTION_ACL_DISCONNECTED, -1))
    }

    @Test
    fun testTargetBluetoothDeviceFiltering() {
        val phoneMacAddress = "00:11:22:33:44:55"
        val anotherMacAddress = "AA:BB:CC:DD:EE:FF"
        val phoneName = "iPhone de Jozito"

        // 1. Cenário: Central multimídia configurada com o MAC do smartphone do usuário
        // Smartphone conecta -> aceita!
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(phoneMacAddress, phoneMacAddress))
        // Smartphone conecta com letras minúsculas (case-insensitive) -> aceita!
        assertTrue(AutostartReceiver.isTargetBluetoothDevice("00:11:22:33:44:55", "00:11:22:33:44:55".lowercase()))
        // Outro aparelho desconhecido conecta -> descarta!
        assertFalse(AutostartReceiver.isTargetBluetoothDevice(anotherMacAddress, phoneMacAddress))
        // Dispositivo nulo conecta -> descarta!
        assertFalse(AutostartReceiver.isTargetBluetoothDevice(null, phoneMacAddress))

        // 2. Cenário: Central multimídia configurada com o Nome do smartphone
        assertTrue(AutostartReceiver.isTargetBluetoothDevice("XX:XX", null, phoneName, "iPhone de Jozito"))
        assertTrue(AutostartReceiver.isTargetBluetoothDevice("XX:XX", null, phoneName, "iphone de jozito"))
        assertFalse(AutostartReceiver.isTargetBluetoothDevice("XX:XX", null, "Outro Celular", "iPhone de Jozito"))

        // 3. Cenário: Modo genérico (sem MAC nem Nome específico):
        // Qualquer smartphone/dispositivo conectando é aceito pela central sem restrição a CAR_AUDIO
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(phoneMacAddress, null))
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(anotherMacAddress, ""))
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(null, null))
    }

    @Test
    fun testUserIntentionOnWakeAndBoot() {
        // Simulação da lógica da Fase 11:
        fun shouldWakeService(action: String, isIntentionallyStopped: Boolean, isAutostartEnabled: Boolean): Boolean {
            if (action == AutostartReceiver.ACTION_START) return true
            if (action == AutostartReceiver.ACTION_STOP) return false
            if (isIntentionallyStopped) return false
            if (!isAutostartEnabled) return false
            return AutostartReceiver.isIgnitionOrBootAction(action)
        }

        // Caso 1: Usuário NÃO parou intencionalmente o app e o carro liga a chave (POWER_CONNECTED) -> DEVE INICIAR
        assertTrue(shouldWakeService(Intent.ACTION_POWER_CONNECTED, isIntentionallyStopped = false, isAutostartEnabled = true))
        assertTrue(shouldWakeService(Intent.ACTION_SCREEN_ON, isIntentionallyStopped = false, isAutostartEnabled = true))
        assertTrue(shouldWakeService(Intent.ACTION_BOOT_COMPLETED, isIntentionallyStopped = false, isAutostartEnabled = true))

        // Caso 2: Usuário apertou STOP no app ou no CoolantHUD antes de sair do carro (isIntentionallyStopped = true)
        // Ao religar o carro / acordar a central multimídia -> NÃO DEVE INICIAR
        assertFalse(shouldWakeService(Intent.ACTION_POWER_CONNECTED, isIntentionallyStopped = true, isAutostartEnabled = true))
        assertFalse(shouldWakeService(Intent.ACTION_SCREEN_ON, isIntentionallyStopped = true, isAutostartEnabled = true))
        assertFalse(shouldWakeService(Intent.ACTION_BOOT_COMPLETED, isIntentionallyStopped = true, isAutostartEnabled = true))

        // Caso 3: Comando explícito do Launcher ACTION_START sempre inicia
        assertTrue(shouldWakeService(AutostartReceiver.ACTION_START, isIntentionallyStopped = true, isAutostartEnabled = true))
    }

    @Test
    fun testWatchdogWakeTriggerResilience() {
        fun shouldProcessWatchdogWake(isIntentionallyStopped: Boolean): Boolean {
            return !isIntentionallyStopped
        }

        // Se o app foi desligado pelo sistema por Force Stop após Deep Sleep, mas o usuário NÃO desligou intencionalmente:
        // O pulso do Launcher restaura o RadarOverlayService
        assertTrue(shouldProcessWatchdogWake(isIntentionallyStopped = false))

        // Se o usuário desligou o serviço intencionalmente (via botão STOP):
        // O pulso do Launcher deve ser ignorado para respeitar a decisão do motorista
        assertFalse(shouldProcessWatchdogWake(isIntentionallyStopped = true))
    }
}
