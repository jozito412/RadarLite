package com.jozito.radarlite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarSyncManagerTest {

    @Test
    fun parseCsvLine_validCompleteLine_parsesAllFields() {
        val line = "-25.4284,-49.2733,FIXED,60,OSM,1,,,180.0"
        val radar = RadarSyncManager.parseCsvLine(line)

        assertNotNull(radar)
        radar?.let {
            assertEquals(-25.4284, it.latitude, 0.0001)
            assertEquals(-49.2733, it.longitude, 0.0001)
            assertEquals("FIXED", it.type)
            assertEquals(60, it.speedLimit)
            assertEquals("OSM", it.source)
            assertEquals(180.0, it.bearing!!, 0.0001)
        }
    }

    @Test
    fun parseCsvLine_emptySpeedLimitAndBearing_defaultsToZeroAndNull() {
        val line = "-24.009795,-54.306174,FIXED,,OSM,1,,,"
        val radar = RadarSyncManager.parseCsvLine(line)

        assertNotNull(radar)
        radar?.let {
            assertEquals(-24.009795, it.latitude, 0.000001)
            assertEquals(-54.306174, it.longitude, 0.000001)
            assertEquals("FIXED", it.type)
            assertEquals(0, it.speedLimit)
            assertNull(it.bearing)
        }
    }

    @Test
    fun parseCsvLine_headerLine_returnsNull() {
        val header = "lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg"
        val radar = RadarSyncManager.parseCsvLine(header)
        assertNull(radar)
    }

    @Test
    fun parseCsvLine_blankOrMalformed_returnsNull() {
        assertNull(RadarSyncManager.parseCsvLine(""))
        assertNull(RadarSyncManager.parseCsvLine("   "))
        assertNull(RadarSyncManager.parseCsvLine("invalid,line"))
    }

    @Test
    fun stateCsvUrls_containsAllFourStates() {
        val urls = RadarSyncManager.STATE_CSV_URLS
        assertEquals(4, urls.size)
        assertTrue(urls.any { it.contains("/PR/") })
        assertTrue(urls.any { it.contains("/SC/") })
        assertTrue(urls.any { it.contains("/SP/") })
        assertTrue(urls.any { it.contains("/MS/") })
    }
}
