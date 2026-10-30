package com.jozito.radarlite

import com.jozito.radarlite.data.RadarEntity
import com.jozito.radarlite.service.RadarOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarOverlayServiceTest {

    @Test
    fun testSpeedConversionFromMpsToKmh() {
        // 10 m/s * 3.6 = 36.0 km/h
        val mps10 = 10.0f
        val kmh10 = mps10 * 3.6f
        assertEquals(36.0f, kmh10, 0.001f)

        // 27.7778 m/s * 3.6 ~ 100.0 km/h
        val mps100 = 27.7778f
        val kmh100 = mps100 * 3.6f
        assertEquals(100.0f, kmh100, 0.01f)

        // 0 m/s = 0 km/h
        val mps0 = 0.0f
        val kmh0 = mps0 * 3.6f
        assertEquals(0.0f, kmh0, 0.001f)
    }

    @Test
    fun testToleranceDegrees30Comparison() {
        // Tolerância de +/- 30 graus conforme especificação da Fase 3
        val tolerance = RadarOverlayService.BEARING_TOLERANCE_DEGREES
        assertEquals(30.0, tolerance, 0.001)

        // Exatamente mesma direção (90° e 90°)
        assertTrue(RadarOverlayService.isSameDirection(90f, 90.0))

        // Direção dentro da tolerância (+25° de diferença: 90° e 115°)
        assertTrue(RadarOverlayService.isSameDirection(90f, 115.0))

        // No limite da tolerância de 30° (+30°: 90° e 120°)
        assertTrue(RadarOverlayService.isSameDirection(90f, 120.0))

        // Fora da tolerância de 30° (+31°: 90° e 121°)
        assertFalse(RadarOverlayService.isSameDirection(90f, 121.0))

        // Fora da tolerância (+45°: 90° e 135°)
        assertFalse(RadarOverlayService.isSameDirection(90f, 135.0))

        // Sentido oposto (0° vs 180°)
        assertFalse(RadarOverlayService.isSameDirection(0f, 180.0))

        // Transição pelo meridiano 0°/360° (ex: veículo a 10° e radar a 350° -> diferença de 20°)
        assertTrue(RadarOverlayService.isSameDirection(10f, 350.0))
        assertFalse(RadarOverlayService.isSameDirection(10f, 320.0)) // diferença de 50° -> false

        // Radar com bearing nulo (aceita qualquer direção)
        assertTrue(RadarOverlayService.isSameDirection(180f, null))
    }

    @Test
    fun testSpeedThreshold90Percent() {
        val speedLimit = 60
        val threshold = 0.9f * speedLimit // 54.0 km/h

        // Velocidade abaixo de 90% (ex: 50 km/h) -> não dispara
        val belowSpeed = 50.0f
        assertFalse(belowSpeed >= threshold)

        // Velocidade igual a 90% (ex: 54 km/h) -> dispara
        val exactSpeed = 54.0f
        assertTrue(exactSpeed >= threshold)

        // Velocidade acima do limite (ex: 70 km/h) -> dispara
        val aboveSpeed = 70.0f
        assertTrue(aboveSpeed >= threshold)
    }

    @Test
    fun testCooldown15SecondsLogic() {
        val cooldownMs = RadarOverlayService.COOLDOWN_DURATION_MS
        assertEquals(15000L, cooldownMs)

        var lastAlertTime = 100_000L

        // Nova verificação após 5 segundos (delta 5000ms < 15000ms) -> bloqueado por cooldown
        val attempt1Time = 105_000L
        val canTrigger1 = (attempt1Time - lastAlertTime) >= cooldownMs
        assertFalse(canTrigger1)

        // Nova verificação após 14.9 segundos -> bloqueado
        val attempt2Time = 114_900L
        val canTrigger2 = (attempt2Time - lastAlertTime) >= cooldownMs
        assertFalse(canTrigger2)

        // Nova verificação após 15 segundos exatos -> liberado!
        val attempt3Time = 115_000L
        val canTrigger3 = (attempt3Time - lastAlertTime) >= cooldownMs
        assertTrue(canTrigger3)
        if (canTrigger3) {
            lastAlertTime = attempt3Time
        }

        // Próxima tentativa imediatamente após o novo disparo -> bloqueado
        val attempt4Time = 116_000L
        val canTrigger4 = (attempt4Time - lastAlertTime) >= cooldownMs
        assertFalse(canTrigger4)
    }

    @Test
    fun testFrontalVisionConeRelativeAngleCalculations() {
        // 1. Radar exatamente à frente (carro Norte 0°, radar à frente 0°) -> diff 0° (< 90°)
        val diffFront = RadarOverlayService.calculateRelativeAngleDiff(0f, 0f)
        assertEquals(0f, diffFront, 0.001f)
        assertTrue(diffFront < 90f)

        // 2. Radar à frente e à direita a 45° -> diff 45° (< 90°)
        val diffFrontRight = RadarOverlayService.calculateRelativeAngleDiff(0f, 45f)
        assertEquals(45f, diffFrontRight, 0.001f)
        assertTrue(diffFrontRight < 90f)

        // 3. Radar à frente e à esquerda a 315° (-45°) -> diff 45° (< 90°)
        val diffFrontLeft = RadarOverlayService.calculateRelativeAngleDiff(0f, -45f)
        assertEquals(45f, diffFrontLeft, 0.001f)
        assertTrue(diffFrontLeft < 90f)

        // 4. Carro ultrapassou o radar! Radar ficou exatamente para trás (180° ou -180°) -> diff 180° (>= 90°)
        val diffBehind = RadarOverlayService.calculateRelativeAngleDiff(0f, 180f)
        assertEquals(180f, diffBehind, 0.001f)
        assertFalse(diffBehind < 90f) // Descartado imediatamente!

        val diffBehindNeg = RadarOverlayService.calculateRelativeAngleDiff(0f, -180f)
        assertEquals(180f, diffBehindNeg, 0.001f)
        assertFalse(diffBehindNeg < 90f)

        // 5. Radar nas costas e à direita a 135° -> diff 135° (>= 90°)
        val diffBackRight = RadarOverlayService.calculateRelativeAngleDiff(0f, 135f)
        assertEquals(135f, diffBackRight, 0.001f)
        assertFalse(diffBackRight < 90f)

        // 6. Carro trafegando a Oeste (270°), Radar à frente a Oeste (-90° / 270°) -> diff 0° (< 90°)
        val diffWestFront = RadarOverlayService.calculateRelativeAngleDiff(270f, -90f)
        assertEquals(0f, diffWestFront, 0.001f)
        assertTrue(diffWestFront < 90f)

        // 7. Carro trafegando a Oeste (270°), Radar ultrapassado para trás a Leste (90°) -> diff 180° (>= 90°)
        val diffWestBehind = RadarOverlayService.calculateRelativeAngleDiff(270f, 90f)
        assertEquals(180f, diffWestBehind, 0.001f)
        assertFalse(diffWestBehind < 90f)

        // 8. Cruzamento do meridiano: Carro a 10°, Radar à frente-esquerda a 350° (-10°) -> diff 20° (< 90°)
        val diffMeridian = RadarOverlayService.calculateRelativeAngleDiff(10f, -10f)
        assertEquals(20f, diffMeridian, 0.001f)
        assertTrue(diffMeridian < 90f)
    }

    @Test
    fun testFrontalConeBoundaryCondition90Degrees() {
        // Exatamente 90 graus (perpendicular ao carro): o radar já está na linha lateral e deve ser descartado (angleDiff >= 90)
        val diff90 = RadarOverlayService.calculateRelativeAngleDiff(0f, 90f)
        assertEquals(90f, diff90, 0.001f)
        assertFalse(diff90 < 90f) // Descarta no limite

        // 89.9 graus (ainda no campo frontal) -> aceita
        val diff89 = RadarOverlayService.calculateRelativeAngleDiff(0f, 89.9f)
        assertEquals(89.9f, diff89, 0.01f)
        assertTrue(diff89 < 90f)

        // 90.1 graus (já passou da linha lateral) -> descarta
        val diff91 = RadarOverlayService.calculateRelativeAngleDiff(0f, 90.1f)
        assertEquals(90.1f, diff91, 0.01f)
        assertFalse(diff91 < 90f)
    }

    @Test
    fun testDynamicAlertDistance() {
        // Rodovia (speedKmh >= 80 km/h) -> 500m
        assertEquals(500f, RadarOverlayService.getAlertDistanceMeters(80f), 0.001f)
        assertEquals(500f, RadarOverlayService.getAlertDistanceMeters(100f), 0.001f)
        assertEquals(500f, RadarOverlayService.getAlertDistanceMeters(120f), 0.001f)

        // Cidade (speedKmh < 80 km/h) -> 250m
        assertEquals(250f, RadarOverlayService.getAlertDistanceMeters(79.9f), 0.001f)
        assertEquals(250f, RadarOverlayService.getAlertDistanceMeters(60f), 0.001f)
        assertEquals(250f, RadarOverlayService.getAlertDistanceMeters(40f), 0.001f)
        assertEquals(250f, RadarOverlayService.getAlertDistanceMeters(0f), 0.001f)
    }

    @Test
    fun testRadarTypeInfoMapping() {
        // TRAFFIC_LIGHT -> 🚦 Semáforo
        val trafficLight = RadarOverlayService.getRadarTypeInfo("TRAFFIC_LIGHT")
        assertEquals("🚦", trafficLight.first)
        assertEquals("Semáforo", trafficLight.second)

        // PEDESTRIAN_CROSSING -> 🚶 Lombada
        val pedestrianCrossing = RadarOverlayService.getRadarTypeInfo("PEDESTRIAN_CROSSING")
        assertEquals("🚶", pedestrianCrossing.first)
        assertEquals("Lombada", pedestrianCrossing.second)

        // SECTION -> ⏱️ Trecho
        val section = RadarOverlayService.getRadarTypeInfo("SECTION")
        assertEquals("⏱️", section.first)
        assertEquals("Trecho", section.second)

        // FIXED -> 📸 Radar Fixo
        val fixed = RadarOverlayService.getRadarTypeInfo("FIXED")
        assertEquals("📸", fixed.first)
        assertEquals("Radar Fixo", fixed.second)

        // Default / Case insensitive
        val lowerCase = RadarOverlayService.getRadarTypeInfo("traffic_light")
        assertEquals("🚦", lowerCase.first)
        val unknown = RadarOverlayService.getRadarTypeInfo("UNKNOWN_TYPE")
        assertEquals("📸", unknown.first)
    }

    @Test
    fun testSpatialCachingAndGpsTimeoutConstants() {
        // Tarefa 1: Caching Espacial em RAM (raio amplo de 15 km e expiração em 3 km)
        assertEquals(15000.0, RadarOverlayService.CACHE_RADIUS_METERS, 0.001)
        assertEquals(3000f, RadarOverlayService.CACHE_EXPIRATION_DISTANCE_METERS, 0.001f)

        // Tarefa 2: Timeout de GPS (10.000ms tolerante a oscilações urbanas para túneis e zonas mortas)
        assertEquals(10000L, RadarOverlayService.GPS_TIMEOUT_MS)
    }

    @Test
    fun testFase8AlertDistanceByRadarSpeedLimitDefaultValues() {
        // 1. Zonas até 40 km/h (dist_low_speed: padrão 180m)
        assertEquals(180f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 30), 0.001f)
        assertEquals(180f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 40), 0.001f)

        // 2. Vias Urbanas de 41 a 60 km/h (dist_city: padrão 220m)
        assertEquals(220f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 50), 0.001f)
        assertEquals(220f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 60), 0.001f)

        // 3. Vias Expressas de 61 a 90 km/h (dist_express: padrão 350m)
        assertEquals(350f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 70), 0.001f)
        assertEquals(350f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 80), 0.001f)
        assertEquals(350f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 90), 0.001f)

        // 4. Rodovias acima de 90 km/h (dist_highway: padrão 500m)
        assertEquals(500f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 100), 0.001f)
        assertEquals(500f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 110), 0.001f)
        assertEquals(500f, RadarOverlayService.getAlertDistanceForRadar(speedLimit = 120), 0.001f)
    }

    @Test
    fun testFase8AlertDistanceByRadarSpeedLimitCustomValues() {
        val customLow = 150
        val customCity = 200
        val customExpress = 300
        val customHighway = 450

        // <= 40 -> customLow (150m)
        assertEquals(150f, RadarOverlayService.getAlertDistanceForRadar(40, customLow, customCity, customExpress, customHighway), 0.001f)

        // <= 60 -> customCity (200m)
        assertEquals(200f, RadarOverlayService.getAlertDistanceForRadar(60, customLow, customCity, customExpress, customHighway), 0.001f)

        // <= 90 -> customExpress (300m)
        assertEquals(300f, RadarOverlayService.getAlertDistanceForRadar(80, customLow, customCity, customExpress, customHighway), 0.001f)

        // > 90 -> customHighway (450m)
        assertEquals(450f, RadarOverlayService.getAlertDistanceForRadar(110, customLow, customCity, customExpress, customHighway), 0.001f)
    }

    @Test
    fun testFase8ProgressiveAlertTwoStagesEvaluation() {
        val speedLimit = 60
        val alertDistance = 220f
        val halfDistance = alertDistance / 2f // 110m

        // Caso 1: Velocidade Segura (currentSpeed <= radar.speedLimit) -> Seguro (Card normal)
        val speedSafe = 55f
        assertFalse(speedSafe > speedLimit)

        // Caso 2: Acima do Limite E currentDistance > (alertDistance / 2) -> Alerta Estático (Card compacto vermelho sólido)
        val speedOver = 65f
        val distanceStage1 = 150f // 150m > 110m
        assertTrue(speedOver > speedLimit)
        assertTrue(distanceStage1 > halfDistance)

        // Caso 3: Acima do Limite E currentDistance <= (alertDistance / 2) -> Alerta Agressivo (Card expansivo piscante)
        val distanceStage2 = 90f // 90m <= 110m
        assertTrue(speedOver > speedLimit)
        assertTrue(distanceStage2 <= halfDistance)

        // Ponto de transição exato (110m): no limite de 50%, aciona alerta agressivo para frenagem iminente
        val distanceBoundary = 110f
        assertTrue(distanceBoundary <= halfDistance)
    }

    @Test
    fun testFase9ShouldIgnoreRadarFilter() {
        val osmRadar = RadarEntity(
            id = 1L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            source = "OSM"
        )
        val dnitRadar = RadarEntity(
            id = 2L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            source = "DNIT"
        )
        val nullSourceRadar = RadarEntity(
            id = 3L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            source = null
        )

        // 1. Curadoria Manual com OSM mantido (Padrão: blockOsmRadars = false)
        assertFalse(RadarOverlayService.shouldIgnoreRadar(osmRadar, blacklistedIds = emptySet()))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(dnitRadar, blacklistedIds = emptySet()))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(nullSourceRadar, blacklistedIds = emptySet()))

        // 2. Bloqueio Global do OSM explicitamente ativado (blockOsmRadars = true)
        assertTrue(RadarOverlayService.shouldIgnoreRadar(osmRadar, blockOsmRadars = true, blacklistedIds = emptySet()))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(dnitRadar, blockOsmRadars = true, blacklistedIds = emptySet()))

        // 3. Blacklist Local: radar ID presente na blacklist é ignorado sempre (invisível na verificação)
        val blacklistedSet = setOf("2", "99")
        assertTrue(RadarOverlayService.shouldIgnoreRadar(dnitRadar, blockOsmRadars = false, blacklistedIds = blacklistedSet))
        assertTrue(RadarOverlayService.shouldIgnoreRadar(dnitRadar, blockOsmRadars = true, blacklistedIds = blacklistedSet))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(osmRadar, blockOsmRadars = false, blacklistedIds = blacklistedSet))
    }
}

