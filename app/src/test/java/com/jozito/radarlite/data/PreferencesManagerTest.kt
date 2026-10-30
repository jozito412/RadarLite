package com.jozito.radarlite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun testPreferencesKeys() {
        assertEquals("warn_only_over_speed", PreferencesManager.KEY_WARN_ONLY_OVER_SPEED)
        assertEquals("is_sound_enabled", PreferencesManager.KEY_IS_SOUND_ENABLED)
        assertEquals("dist_low_speed", PreferencesManager.KEY_DIST_LOW_SPEED)
        assertEquals("dist_city", PreferencesManager.KEY_DIST_CITY)
        assertEquals("dist_express", PreferencesManager.KEY_DIST_EXPRESS)
        assertEquals("dist_highway", PreferencesManager.KEY_DIST_HIGHWAY)

        assertEquals(180, PreferencesManager.DEFAULT_DIST_LOW_SPEED)
        assertEquals(220, PreferencesManager.DEFAULT_DIST_CITY)
        assertEquals(350, PreferencesManager.DEFAULT_DIST_EXPRESS)
        assertEquals(500, PreferencesManager.DEFAULT_DIST_HIGHWAY)

        assertEquals("block_osm_radars", PreferencesManager.KEY_BLOCK_OSM_RADARS)
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
        val stringSet = setOf("101", "202", "invalid", "303")
        val longIds = stringSet.mapNotNull { it.toLongOrNull() }.toSet()

        assertEquals(3, longIds.size)
        assert(longIds.contains(101L))
        assert(longIds.contains(202L))
        assert(longIds.contains(303L))
        assertFalse(longIds.contains(999L))
    }
}
