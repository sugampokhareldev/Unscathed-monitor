package com.unscathed.monitor.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeDetectorTest {
    private val w = LumaGrid.WIDTH
    private val h = LumaGrid.HEIGHT

    private fun solid(value: Int) = LumaGrid(w, h, IntArray(w * h) { value })

    private fun withBlock(base: Int, block: Int, x0: Int, y0: Int, size: Int) =
        LumaGrid(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in x0 until x0 + size && y in y0 until y0 + size) block else base
        })

    @Test
    fun identicalFramesAccumulateStillTime() {
        val d = FreezeDetector()
        d.update(solid(100), 0)
        val r1 = d.update(solid(100), 4_000)
        val r2 = d.update(solid(100), 8_000)
        assertFalse(r1.moving)
        assertEquals(8_000, r2.stillForMs)
    }

    @Test
    fun movementResetsStillTime() {
        val d = FreezeDetector()
        d.update(solid(100), 0)
        d.update(solid(100), 30_000)
        val moved = d.update(withBlock(100, 200, 20, 10, 20), 34_000)
        assertTrue(moved.moving)
        assertEquals(0, moved.stillForMs)
    }

    @Test
    fun sensorNoiseBelowPixelDeltaIsIgnored() {
        val d = FreezeDetector(pixelDelta = 12)
        d.update(solid(100), 0)
        val r = d.update(solid(108), 4_000)
        assertFalse(r.moving)
        assertEquals(0.0, r.changedFraction, 0.0)
    }

    @Test
    fun maskedRegionChangesDoNotCountAsMotion() {
        val d = FreezeDetector()
        val mask = RegionMask(top = 0.2f)
        d.update(solid(100), 0, mask)
        // A "clock" ticking in the top ~13% of the screen.
        val r = d.update(withBlock(100, 250, 30, 0, 6), 4_000, mask)
        assertFalse(r.moving)

        val unmasked = FreezeDetector()
        unmasked.update(solid(100), 0)
        assertTrue(unmasked.update(withBlock(100, 250, 30, 0, 6), 4_000).moving)
    }

    @Test
    fun resolutionChangeCountsAsMotion() {
        val d = FreezeDetector()
        d.update(solid(100), 0)
        val r = d.update(LumaGrid(10, 10, IntArray(100) { 100 }), 60_000)
        assertTrue(r.moving)
        assertEquals(0, r.stillForMs)
    }
}
