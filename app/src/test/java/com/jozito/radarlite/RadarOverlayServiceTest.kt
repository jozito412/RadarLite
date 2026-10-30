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

    @Test
    fun testFase92DirectionalVectorBlacklist() {
        val radar = RadarEntity(
            id = 4092L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            source = "OSM"
        )
        // Banimento do vetor Sul (185º)
        val approaches = setOf("4092_185")

        // 1. Carro vindo a 185º (mesmo sentido) -> BLOQUEADO
        assertTrue(RadarOverlayService.isApproachBanned(4092L, 185f, approaches))
        assertTrue(RadarOverlayService.shouldIgnoreRadar(radar, blacklistedIds = approaches, vehicleBearing = 185f))

        // 2. Carro vindo a 210º (diferença de 25º <= 60º do cone de bloqueio) -> BLOQUEADO
        assertTrue(RadarOverlayService.isApproachBanned(4092L, 210f, approaches))
        assertTrue(RadarOverlayService.shouldIgnoreRadar(radar, blacklistedIds = approaches, vehicleBearing = 210f))

        // 3. Carro vindo no limite de 60º (245º - 185º = 60º) -> BLOQUEADO
        assertTrue(RadarOverlayService.isApproachBanned(4092L, 245f, approaches))

        // 4. Carro vindo fora do cone de 60º (246º - 185º = 61º) -> LIBERADO
        assertFalse(RadarOverlayService.isApproachBanned(4092L, 246f, approaches))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(radar, blacklistedIds = approaches, vehicleBearing = 246f))

        // 5. Carro no sentido Norte Oposto (5º, diferença de 180º) -> LIBERADO (alerta normal ativo para o sentido Norte!)
        assertFalse(RadarOverlayService.isApproachBanned(4092L, 5f, approaches))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(radar, blacklistedIds = approaches, vehicleBearing = 5f))

        // 6. Carro na via transversal (95º, diferença de 90º) -> LIBERADO
        assertFalse(RadarOverlayService.isApproachBanned(4092L, 95f, approaches))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(radar, blacklistedIds = approaches, vehicleBearing = 95f))

        // 7. Radar diferente (ex: ID 9999) -> LIBERADO
        assertFalse(RadarOverlayService.isApproachBanned(9999L, 185f, approaches))
    }

    @Test
    fun testFase10AreClonesConditions() {
        // Base radar na Praça Tiradentes, Curitiba (-25.4284, -49.2733)
        val baseRadar = RadarEntity(
            id = 1L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            bearing = 90.0,
            source = "DNIT"
        )

        // 1. Proximidade: distância >= 35m (aprox. 50m ao norte) -> NÃO é clone
        val farRadar = baseRadar.copy(
            id = 2L,
            latitude = -25.4284 + 0.00045, // ~50 metros de distância
            longitude = -49.2733
        )
        assertFalse(RadarOverlayService.areClones(baseRadar, farRadar))

        // 2. Mesma Regra: velocidade diferente (40 km/h marginal vs 60 km/h expressa a 15m) -> NÃO é clone
        val diffSpeedRadar = baseRadar.copy(
            id = 3L,
            latitude = -25.4284 + 0.0001, // ~11 metros
            speedLimit = 40
        )
        assertFalse(RadarOverlayService.areClones(baseRadar, diffSpeedRadar))

        // 3. Mesmo Tipo: tipos diferentes (TRAFFIC_LIGHT vs FIXED no mesmo semáforo) -> NÃO é clone
        val diffTypeRadar = baseRadar.copy(
            id = 4L,
            latitude = -25.4284 + 0.00005,
            type = "TRAFFIC_LIGHT"
        )
        assertFalse(RadarOverlayService.areClones(baseRadar, diffTypeRadar))

        // 4. Conflito de Direção:
        // Caso 4a: Ambos possuem direção com diferença < 45° (90° vs 110°, diff 20°) -> É CLONE
        val closeBearingRadar = baseRadar.copy(
            id = 5L,
            latitude = -25.4284 + 0.0001,
            bearing = 110.0
        )
        assertTrue(RadarOverlayService.areClones(baseRadar, closeBearingRadar))

        // Caso 4b: Ambos possuem direção com diferença >= 45° (90° vs 180°, diff 90° - sentido transversal/oposto) -> NÃO é clone
        val diffBearingRadar = baseRadar.copy(
            id = 6L,
            latitude = -25.4284 + 0.0001,
            bearing = 180.0
        )
        assertFalse(RadarOverlayService.areClones(baseRadar, diffBearingRadar))

        // Caso 4c: Um deles não possui direção (bearing = null atua radialmente 360°) -> É CLONE
        val nullBearingRadar = baseRadar.copy(
            id = 7L,
            latitude = -25.4284 + 0.0001,
            bearing = null
        )
        assertTrue(RadarOverlayService.areClones(baseRadar, nullBearingRadar))

        // Caso 4d: Ambos sem direção a 15 metros -> É CLONE
        val nullBearingRadar2 = nullBearingRadar.copy(id = 8L, latitude = -25.4284 + 0.00012)
        assertTrue(RadarOverlayService.areClones(nullBearingRadar, nullBearingRadar2))
    }

    @Test
    fun testFase10DeduplicateRadarsSurvivalRules() {
        // Cenário 1: Campeão com direção ganha de clone sem direção
        val omniRadar = RadarEntity(
            id = 10L,
            latitude = -25.4284,
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            bearing = null,
            source = "OSM"
        )
        val directionalRadar = RadarEntity(
            id = 20L,
            latitude = -25.4284 + 0.0001, // 11m
            longitude = -49.2733,
            speedLimit = 60,
            type = "FIXED",
            bearing = 90.0,
            source = "DNIT"
        )

        // Testando independente da ordem de entrada na lista
        val result1 = RadarOverlayService.deduplicateRadars(listOf(omniRadar, directionalRadar))
        assertEquals(1, result1.size)
        assertEquals(20L, result1[0].id) // directionalRadar é o campeão!

        val result2 = RadarOverlayService.deduplicateRadars(listOf(directionalRadar, omniRadar))
        assertEquals(1, result2.size)
        assertEquals(20L, result2[0].id)

        // Cenário 2: Se nenhum clone tiver direção, prioriza o source com '+' (dados enriquecidos)
        val simpleOsm = omniRadar.copy(id = 30L, source = "OSM")
        val enrichedOsm = omniRadar.copy(id = 40L, source = "OSM+")

        val result3 = RadarOverlayService.deduplicateRadars(listOf(simpleOsm, enrichedOsm))
        assertEquals(1, result3.size)
        assertEquals(40L, result3[0].id) // enrichedOsm tem '+' e sobrevive!

        // Cenário 3: Empate total (ambos sem direção, fontes sem '+') -> mantém o primeiro da lista
        val tieFirst = omniRadar.copy(id = 50L, source = "OSM")
        val tieSecond = omniRadar.copy(id = 60L, source = "OSM")

        val result4 = RadarOverlayService.deduplicateRadars(listOf(tieFirst, tieSecond))
        assertEquals(1, result4.size)
        assertEquals(50L, result4[0].id) // primeiro da lista sobrevive!

        // Cenário 4: Cruzamento complexo com sentidos opostos (0° e 180°) e um radar omnidirecional fantasma do OSM
        val northRadar = RadarEntity(id = 100L, latitude = -25.4284, longitude = -49.2733, speedLimit = 60, type = "FIXED", bearing = 0.0, source = "DNIT")
        val southRadar = RadarEntity(id = 200L, latitude = -25.4284 + 0.0001, longitude = -49.2733, speedLimit = 60, type = "FIXED", bearing = 180.0, source = "DNIT")
        val ghostOmni = RadarEntity(id = 300L, latitude = -25.4284 + 0.00005, longitude = -49.2733, speedLimit = 60, type = "FIXED", bearing = null, source = "OSM")

        val complexResult = RadarOverlayService.deduplicateRadars(listOf(northRadar, southRadar, ghostOmni))
        assertEquals(2, complexResult.size)
        assertTrue(complexResult.any { it.id == 100L }) // Sentido Norte preservado
        assertTrue(complexResult.any { it.id == 200L }) // Sentido Sul preservado
        assertFalse(complexResult.any { it.id == 300L }) // Fantasma omnidirecional removido!

        // Cenário 5: Marginal lenta colada em via expressa rápida (não são clones por terem regras distintas)
        val marginal40 = RadarEntity(id = 401L, latitude = -25.4284, longitude = -49.2733, speedLimit = 40, type = "FIXED", bearing = 90.0, source = "OSM")
        val expressa80 = RadarEntity(id = 402L, latitude = -25.4284 + 0.0001, longitude = -49.2733, speedLimit = 80, type = "FIXED", bearing = 90.0, source = "OSM")

        val speedsResult = RadarOverlayService.deduplicateRadars(listOf(marginal40, expressa80))
        assertEquals(2, speedsResult.size)
        assertTrue(speedsResult.any { it.id == 401L })
        assertTrue(speedsResult.any { it.id == 402L })
    }

    @Test
    fun testFase93HybridBlacklistGlobalHammerAndDirectionalCone() {
        // Cenário 1: Marreta Global ("${radarId}_GLOBAL") bloqueia em qualquer sentido (360 graus)
        val globalBannedApproaches = setOf("9001_GLOBAL", "9002_180")

        // Radar 9001 foi banido com Long Press (Global)
        assertTrue(RadarOverlayService.isApproachBanned(9001L, 0f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9001L, 90f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9001L, 180f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9001L, 270f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9001L, 359f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9001L, null, globalBannedApproaches))

        // Cenário 2: Radar 9002 foi banido com Toque Rápido no sentido Sul (180°)
        // Bloqueia com +/- 60° (entre 120° e 240°)
        assertTrue(RadarOverlayService.isApproachBanned(9002L, 180f, globalBannedApproaches))
        assertTrue(RadarOverlayService.isApproachBanned(9002L, 130f, globalBannedApproaches)) // diff 50° <= 60°
        assertTrue(RadarOverlayService.isApproachBanned(9002L, 230f, globalBannedApproaches)) // diff 50° <= 60°
        assertTrue(RadarOverlayService.isApproachBanned(9002L, 120f, globalBannedApproaches)) // diff 60° <= 60°
        assertTrue(RadarOverlayService.isApproachBanned(9002L, 240f, globalBannedApproaches)) // diff 60° <= 60°

        // Não bloqueia fora do cone de 60° (sentido Norte, Leste, Oeste)
        assertFalse(RadarOverlayService.isApproachBanned(9002L, 0f, globalBannedApproaches)) // diff 180° > 60°
        assertFalse(RadarOverlayService.isApproachBanned(9002L, 90f, globalBannedApproaches)) // diff 90° > 60°
        assertFalse(RadarOverlayService.isApproachBanned(9002L, 270f, globalBannedApproaches)) // diff 90° > 60°
        assertFalse(RadarOverlayService.isApproachBanned(9002L, 241f, globalBannedApproaches)) // diff 61° > 60°

        // Cenário 3: Radar 9999 não banido -> não bloqueia
        assertFalse(RadarOverlayService.isApproachBanned(9999L, 180f, globalBannedApproaches))

        // Cenário 4: shouldIgnoreRadar integrando com RadarEntity
        val testRadarGlobal = RadarEntity(id = 9001L, latitude = -25.4, longitude = -49.2, speedLimit = 60, type = "FIXED")
        val testRadarVector = RadarEntity(id = 9002L, latitude = -25.4, longitude = -49.2, speedLimit = 60, type = "FIXED")
        val testRadarClean = RadarEntity(id = 9003L, latitude = -25.4, longitude = -49.2, speedLimit = 60, type = "FIXED")

        assertTrue(RadarOverlayService.shouldIgnoreRadar(testRadarGlobal, blacklistedIds = globalBannedApproaches, vehicleBearing = 0f))
        assertTrue(RadarOverlayService.shouldIgnoreRadar(testRadarVector, blacklistedIds = globalBannedApproaches, vehicleBearing = 180f))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(testRadarVector, blacklistedIds = globalBannedApproaches, vehicleBearing = 0f))
        assertFalse(RadarOverlayService.shouldIgnoreRadar(testRadarClean, blacklistedIds = globalBannedApproaches, vehicleBearing = 180f))
    }

    @Test
    fun testTwoWaySyncStateBroadcastConstants() {
        assertEquals("com.jozito412.radarlite.ACTION_STATE_CHANGED", RadarOverlayService.ACTION_STATE_CHANGED)
        assertEquals("is_running", RadarOverlayService.EXTRA_IS_RUNNING)
    }
}

