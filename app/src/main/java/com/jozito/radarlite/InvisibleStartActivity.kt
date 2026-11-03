package com.jozito.radarlite

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.jozito.radarlite.data.PreferencesManager
import com.jozito.radarlite.service.RadarOverlayService

/**
 * Trampoline Activity invisível / transparente para inicialização 100% confiável
 * do RadarOverlayService em primeiro plano a partir de BroadcastReceivers em background (Android 8.0+ / 14+).
 *
 * Como o Android restringe a inicialização direta de Foreground Services a partir de BroadcastReceivers em background,
 * o receiver aciona esta Activity invisível com FLAG_ACTIVITY_NEW_TASK, e no onCreate ela inicia o serviço
 * como um processo em foreground legítimo, chamando finish() imediatamente sem exibir nenhuma UI.
 */
class InvisibleStartActivity : Activity() {

    companion object {
        private const val TAG = "InvisibleStartActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "InvisibleStartActivity executando para inicializar RadarOverlayService...")

        try {
            PreferencesManager.getInstance(this).isServiceIntentionallyStopped = false

            val serviceIntent = Intent(this, RadarOverlayService::class.java).apply {
                action = RadarOverlayService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "RadarOverlayService disparado com sucesso a partir da InvisibleStartActivity.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar RadarOverlayService na InvisibleStartActivity", e)
        } finally {
            finish()
        }
    }
}

