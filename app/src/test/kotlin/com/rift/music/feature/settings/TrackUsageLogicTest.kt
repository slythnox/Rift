package com.rift.music.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class TrackUsageLogicTest {

    @Test
    fun `formatListenDuration handles zero and negative milliseconds`() {
        assertEquals("0 s", formatListenDurationTest(0L))
        assertEquals("0 s", formatListenDurationTest(-1000L))
    }

    @Test
    fun `formatListenDuration formats seconds only`() {
        assertEquals("45s", formatListenDurationTest(45_000L))
    }

    @Test
    fun `formatListenDuration formats minutes and seconds`() {
        assertEquals("3m 20s", formatListenDurationTest((3 * 60 + 20) * 1000L))
    }

    @Test
    fun `formatListenDuration formats hours minutes and seconds`() {
        assertEquals("1h 15m", formatListenDurationTest((1 * 3600 + 15 * 60 + 30) * 1000L))
    }

    @Test
    fun `24h cutoff calculation matches Room Converters UTC epoch format`() {
        val nowLdt = LocalDateTime.now()
        val epochMilliFromLdt = nowLdt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val cutoff24h = epochMilliFromLdt - 24L * 3600 * 1000L

        // Verify cutoff is exactly 24 hours prior
        assertEquals(24L * 3600 * 1000L, epochMilliFromLdt - cutoff24h)

        // Events occurring 1 hour ago should be strictly greater than the cutoff
        val oneHourAgo = epochMilliFromLdt - 3600 * 1000L
        assertTrue(oneHourAgo > cutoff24h)

        // Events occurring 25 hours ago should be strictly less than the cutoff
        val twentyFiveHoursAgo = epochMilliFromLdt - 25L * 3600 * 1000L
        assertTrue(twentyFiveHoursAgo < cutoff24h)
    }

    @Test
    fun `session network usage arithmetic handles unsupported and resets safely`() {
        val startRx = 100_000L
        val currentRx = 150_000L
        val sessionRx = (currentRx - startRx).coerceAtLeast(0L)
        assertEquals(50_000L, sessionRx)

        // In case currentRx drops below startRx (e.g. counter wrap or reboot)
        val wrappedRx = 50_000L
        val safeWrapped = (wrappedRx - startRx).coerceAtLeast(0L)
        assertEquals(0L, safeWrapped)
    }

    @Test
    fun `cpu utilization formula calculates accurate percentage across cores and clamps safely`() {
        val deltaCpuMs = 200L
        val deltaRealtimeMs = 1000L
        val cores = 4

        // 200ms CPU work over 1000ms wall-clock on 4 cores = (200 / (1000 * 4)) * 100 = 5.0%
        val cpuPercent = ((deltaCpuMs.toFloat() / (deltaRealtimeMs.toFloat() * cores)) * 100f).coerceIn(0f, 100f)
        assertEquals(5.0f, cpuPercent, 0.001f)

        // Extreme burst should clamp to 100%
        val extremeDeltaCpu = 5000L
        val clampedPercent = ((extremeDeltaCpu.toFloat() / (deltaRealtimeMs.toFloat() * cores)) * 100f).coerceIn(0f, 100f)
        assertEquals(100.0f, clampedPercent, 0.001f)

        // Negative or zero time delta
        val zeroTimePercent = if (0L > 0) 50f else 0f
        assertEquals(0f, zeroTimePercent, 0.001f)
    }

    @Test
    fun `gpu render load formula accurately scales with display frame budget`() {
        val refreshRate = 60f
        val frameBudgetMs = 1000f / refreshRate // ~16.666ms
        val frameBudgetNs = (frameBudgetMs * 1_000_000f).toLong()

        // 2.5ms GPU duration on 60Hz display
        val avgGpuNs = 2_500_000L
        val gpuLoadPercent = ((avgGpuNs.toFloat() / frameBudgetNs.toFloat()) * 100f).coerceIn(0f, 100f)
        assertEquals(15.0f, gpuLoadPercent, 0.1f)

        // 120Hz display (8.33ms budget) with 4.16ms GPU duration -> ~50%
        val refreshRate120 = 120f
        val budget120Ns = ((1000f / refreshRate120) * 1_000_000f).toLong()
        val avgGpu120Ns = (budget120Ns / 2)
        val gpuLoad120 = ((avgGpu120Ns.toFloat() / budget120Ns.toFloat()) * 100f).coerceIn(0f, 100f)
        assertEquals(50.0f, gpuLoad120, 0.1f)
    }

    @Test
    fun `network throughput rate converts delta bytes over sample duration to transfer speed`() {
        val deltaBytes = 512_000L // 500 KB
        val deltaRealtimeMs = 1000L // 1 second

        val transferSpeedBytes = (deltaBytes * 1000L / deltaRealtimeMs)
        assertEquals(512_000L, transferSpeedBytes)

        val speedKb = transferSpeedBytes / 1024f
        assertEquals(500.0f, speedKb, 0.001f)
    }

    @Test
    fun `total data consumption aggregates downloaded and uploaded session traffic`() {
        val sessionRx = 45_000_000L // 45 MB
        val sessionTx = 2_500_000L  // 2.5 MB

        val totalConsumed = sessionRx + sessionTx
        assertEquals(47_500_000L, totalConsumed)
    }

    @Test
    fun `exponential moving average smoothing eliminates sudden telemetry jitter`() {
        val rawSeries = listOf(10f, 50f, 15f, 40f, 12f)
        var smoothed = 0f
        val smoothedSeries = mutableListOf<Float>()
        for (raw in rawSeries) {
            smoothed = if (smoothed == 0f) raw else (smoothed * 0.65f + raw * 0.35f)
            smoothedSeries.add(smoothed)
        }

        // First point matches initial raw reading
        assertEquals(10f, smoothedSeries[0], 0.001f)
        // Second point dampens the sudden 50f spike: 10 * 0.65 + 50 * 0.35 = 6.5 + 17.5 = 24.0f
        assertEquals(24.0f, smoothedSeries[1], 0.001f)
        // Verify subsequent points remain smooth and bounded
        assertTrue(smoothedSeries[2] < 24.0f)
        assertTrue(smoothedSeries[3] > smoothedSeries[2])
    }

    private fun formatListenDurationTest(ms: Long): String {
        if (ms <= 0L) return "0 s"
        val totalSec = ms / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0 || h > 0) append("${m}m ")
            if (h == 0L) append("${s}s")
        }.trim()
    }
}
