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

        // Conexão Bluetooth (carro conectando ao smartphone ou multimídia conectando ao celular)
        assertTrue(AutostartReceiver.isAutostartAction(BluetoothDevice.ACTION_ACL_CONNECTED))

        // Atualização de aplicativo em segundo plano
        assertTrue(AutostartReceiver.isAutostartAction(Intent.ACTION_MY_PACKAGE_REPLACED))

        // QuickBoot automotivo (ignição do veículo ligada)
        assertTrue(AutostartReceiver.isAutostartAction("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(AutostartReceiver.isAutostartAction("com.htc.intent.action.QUICKBOOT_POWERON"))

        // Controle remoto por Launcher customizado (ex: CoolantHUD)
        assertEquals("com.jozito412.radarlite.ACTION_START", AutostartReceiver.ACTION_START)
        assertTrue(AutostartReceiver.isAutostartAction(AutostartReceiver.ACTION_START))
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

        // Trava lógica: Se o serviço JÁ ESTÁ RODANDO (ex: múltiplos disparos simultâneos ou launcher re-enviando comando),
        // o receptor descarta novos disparos para evitar concorrência e exceções
        assertFalse(AutostartReceiver.shouldStartService(BluetoothDevice.ACTION_ACL_CONNECTED, isServiceRunning = true))
        assertFalse(AutostartReceiver.shouldStartService(Intent.ACTION_BOOT_COMPLETED, isServiceRunning = true))
        assertFalse(AutostartReceiver.shouldStartService(AutostartReceiver.ACTION_START, isServiceRunning = true))

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
        val carMacAddress = "00:11:22:33:44:55"
        val headphonesMacAddress = "AA:BB:CC:DD:EE:FF"

        // 1. Se configurado com o MAC do carro:
        // O carro conecta -> aceita!
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(carMacAddress, carMacAddress))
        // Carro conecta com letras minúsculas (case-insensitive) -> aceita!
        assertTrue(AutostartReceiver.isTargetBluetoothDevice("00:11:22:33:44:55", "00:11:22:33:44:55".lowercase()))
        // Fones de ouvido conectam -> descarta!
        assertFalse(AutostartReceiver.isTargetBluetoothDevice(headphonesMacAddress, carMacAddress))
        // Dispositivo nulo conecta -> descarta!
        assertFalse(AutostartReceiver.isTargetBluetoothDevice(null, carMacAddress))

        // 2. Se o usuário configurou para "Qualquer dispositivo" (MAC vazio ou nulo):
        // Qualquer dispositivo conecta -> aceita!
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(carMacAddress, null))
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(headphonesMacAddress, ""))
        assertTrue(AutostartReceiver.isTargetBluetoothDevice(null, null))
    }
}
