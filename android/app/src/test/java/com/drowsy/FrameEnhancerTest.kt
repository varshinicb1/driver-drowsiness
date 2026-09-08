package com.drowsy

import com.drowsy.perception.FrameEnhancer
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEnhancerTest {

    @Test
    fun adaptiveEarRelaxesInDarkness() {
        val dark = FrameEnhancer.adaptiveEarThreshold(0.20f, 40f)
        val bright = FrameEnhancer.adaptiveEarThreshold(0.20f, 140f)
        assertTrue(dark < bright)
    }

    @Test
    fun adaptiveMarStableAcrossLighting() {
        val dark = FrameEnhancer.adaptiveMarThreshold(0.55f, 40f)
        val bright = FrameEnhancer.adaptiveMarThreshold(0.55f, 140f)
        assertTrue(dark <= bright)
        assertTrue(bright - dark < 0.1f)
    }
}
