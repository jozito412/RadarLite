package com.jozito.radarlite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun testPreferencesKeys() {
        assertEquals("warn_only_over_speed", PreferencesManager.KEY_WARN_ONLY_OVER_SPEED)
        assertEquals("is_sound_enabled", PreferencesManager.KEY_IS_SOUND_ENABLED)
        assertEquals("is_autostart_enabled", PreferencesManager.KEY_IS_AUTOSTART_ENABLED)
        assertEquals("is_bt_autostart_enabled", PreferencesManager.KEY_IS_BT_AUTOSTART_ENABLED)
        assertEquals("dist_low_speed", PreferencesManager.KEY_DIST_LOW_SPEED)
        assertEquals("dist_city", PreferencesManager.KEY_DIST_CITY)
        assertEquals("dist_express", PreferencesManager.KEY_DIST_EXPRESS)
        assertEquals("dist_highway", PreferencesManager.KEY_DIST_HIGHWAY)

        assertEquals(180, PreferencesManager.DEFAULT_DIST_LOW_SPEED)
        assertEquals(220, PreferencesManager.DEFAULT_DIST_CITY)
        assertEquals(350, PreferencesManager.DEFAULT_DIST_EXPRESS)
        assertEquals(500, PreferencesManager.DEFAULT_DIST_HIGHWAY)

        assertEquals("block_osm_radars", PreferencesManager.KEY_BLOCK_OSM_RADARS)
        assertEquals("banned_approaches", PreferencesManager.KEY_BANNED_APPROACHES)
        assertEquals("blacklisted_radar_ids", PreferencesManager.KEY_BLACKLISTED_RADAR_IDS)
    }

    @Test
    fun testSpeedEvaluationWithPreferences() {
        // Cenário 1: warnOnlyOverSpeed = false (aviso a partir de 90%)
        val speedLimit = 80
        val thresholdDefault = 0.9f * speedLimit // 72.0 km/h

        val speed70 = 70.0f
        val speed75 = 75.0f
        val speed85 = 85.0f

        assertFalse(speed70 >= thresholdDefault)
        assert(speed75 >= thresholdDefault)
        assert(speed85 >= thresholdDefault)

        // Cenário 2: warnOnlyOverSpeed = true (aviso estritamente na velocidade máxima do radar)
        val thresholdStrict = speedLimit.toFloat() // 80.0 km/h

        assertFalse(speed75 >= thresholdStrict)
        assert(speed85 >= thresholdStrict)
    }

    @Test
    fun testBanRadarLogic() {
        val bannedIds = mutableSetOf<String>()

        fun isRadarBanned(radarId: Long): Boolean = bannedIds.contains(radarId.toString())
        fun banRadar(radarId: Long) {
            bannedIds.add(radarId.toString())
        }

        assertFalse(isRadarBanned(12345L))
        banRadar(12345L)
        assert(isRadarBanned(12345L))
        assertFalse(isRadarBanned(67890L))

        // Remoção (unban)
        fun unbanRadar(radarId: Long) {
            bannedIds.remove(radarId.toString())
        }
        unbanRadar(12345L)
        assertFalse(isRadarBanned(12345L))
    }

    @Test
    fun testBlacklistedRadarLongIdsConversion() {
        val stringSet = setOf("101", "202_180", "4092_185", "invalid", "303")
        val longIds = stringSet.mapNotNull { entry ->
            val idPart = if (entry.contains("_")) entry.substringBefore('_') else entry
            idPart.toLongOrNull()
        }.toSet()

        assertEquals(4, longIds.size)
        assert(longIds.contains(101L))
        assert(longIds.contains(202L))
        assert(longIds.contains(4092L))
        assert(longIds.contains(303L))
        assertFalse(longIds.contains(999L))
    }

    @Test
    fun testFase92DirectionalVectorBlacklistLogic() {
        val bannedApproaches = mutableSetOf<String>()

        fun banApproach(radarId: Long, bearing: Double) {
            val norm = ((bearing.toInt() % 360) + 360) % 360
            bannedApproaches.add("${radarId}_$norm")
        }

        fun isApproachBanned(radarId: Long, currentBearing: Double, tolerance: Double = 60.0): Boolean {
            val prefix = "${radarId}_"
            val idStr = radarId.toString()
            val entries = bannedApproaches.filter { it == idStr || it.startsWith(prefix) }
            if (entries.isEmpty()) return false

            for (entry in entries) {
                if (entry == idStr) return true
                val bannedBearing = entry.removePrefix(prefix).toDoubleOrNull() ?: continue
                var angleDiff = Math.abs(currentBearing - bannedBearing) % 360.0
                if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff
                if (angleDiff <= tolerance) return true
            }
            return false
        }

        // Bane radar 4092 viajando para o Sul (185º)
        banApproach(4092L, 185.0)
        assert(bannedApproaches.contains("4092_185"))

        // 1. Mesmo sentido exato (185°) -> BLOQUEADO
        assert(isApproachBanned(4092L, 185.0))

        // 2. Sentido dentro do cone de 60° (ex: 215°, diff 30° <= 60°) -> BLOQUEADO
        assert(isApproachBanned(4092L, 215.0))

        // 3. No limite de 60° (ex: 245°, diff 60.0° <= 60°) -> BLOQUEADO
        assert(isApproachBanned(4092L, 245.0))

        // 4. Fora do cone (ex: 246°, diff 61.0° > 60°) -> LIBERADO
        assertFalse(isApproachBanned(4092L, 246.0))

        // 5. Sentido Norte Oposto (5°, diff 180° > 60°) -> LIBERADO (Radar continua ativo no sentido Norte!)
        assertFalse(isApproachBanned(4092L, 5.0))

        // 6. Via transversal Leste (95°, diff 90° > 60°) -> LIBERADO
        assertFalse(isApproachBanned(4092L, 95.0))

        // 7. Outro radar não banido -> LIBERADO
        assertFalse(isApproachBanned(9999L, 185.0))
    }

    @Test
    fun testFase93HybridBlacklistGlobalAndVectorLogic() {
        val bannedApproaches = mutableSetOf<String>()

        fun banRadarGlobally(radarId: Long) {
            bannedApproaches.add("${radarId}_GLOBAL")
        }

        fun banApproach(radarId: Long, bearing: Double) {
            val norm = ((bearing.toInt() % 360) + 360) % 360
            bannedApproaches.add("${radarId}_$norm")
        }

        fun isRadarGloballyBanned(radarId: Long): Boolean {
            return bannedApproaches.contains("${radarId}_GLOBAL") || bannedApproaches.contains(radarId.toString())
        }

        fun isApproachBanned(radarId: Long, currentBearing: Double, tolerance: Double = 60.0): Boolean {
            val idStr = radarId.toString()
            val globalKey = "${radarId}_GLOBAL"
            if (bannedApproaches.contains(globalKey) || bannedApproaches.contains(idStr)) {
                return true
            }

            val prefix = "${radarId}_"
            val entries = bannedApproaches.filter { it.startsWith(prefix) && it != globalKey }
            if (entries.isEmpty()) return false

            for (entry in entries) {
                val bannedBearing = entry.removePrefix(prefix).toDoubleOrNull() ?: continue
                var angleDiff = Math.abs(currentBearing - bannedBearing) % 360.0
                if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff
                if (angleDiff <= tolerance) return true
            }
            return false
        }

        // Radar 5000: Banimento global via Long Press
        banRadarGlobally(5000L)
        assert(isRadarGloballyBanned(5000L))
        assert(isApproachBanned(5000L, 0.0))
        assert(isApproachBanned(5000L, 90.0))
        assert(isApproachBanned(5000L, 180.0))
        assert(isApproachBanned(5000L, 270.0))

        // Radar 6000: Banimento direcionado apenas no sentido Leste (90°) via Toque Rápido
        banApproach(6000L, 90.0)
        assertFalse(isRadarGloballyBanned(6000L))
        assert(isApproachBanned(6000L, 90.0))
        assert(isApproachBanned(6000L, 120.0)) // diff 30° <= 60°
        assertFalse(isApproachBanned(6000L, 270.0)) // Sentido oposto liberado!

        // Verificação de extração de ID para o diálogo da Blacklist
        val longIds = bannedApproaches.mapNotNull { entry ->
            val idPart = if (entry.contains("_")) entry.substringBefore('_') else entry
            idPart.toLongOrNull()
        }.toSet()

        assert(longIds.contains(5000L))
        assert(longIds.contains(6000L))

        // Remoção unban (remove prefixo "5000_")
        val prefix5000 = "5000_"
        bannedApproaches.removeAll { it == "5000" || it.startsWith(prefix5000) }
        assertFalse(isRadarGloballyBanned(5000L))
        assertFalse(isApproachBanned(5000L, 0.0))
    }
}
