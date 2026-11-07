package com.platojobs.pkotlinapp.ui.home

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 云端读数单位（celsius/percent）要转成界面习惯符号，避免直接显示英文枚举。 */
class ReadingFormatTest {

    private lateinit var original: Locale

    @Before
    fun setUp() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `cloud celsius is rendered as degree symbol`() {
        assertEquals("25.5 ℃", formatReading(25.5, "celsius"))
    }

    @Test
    fun `percent and integer values keep their meaning`() {
        assertEquals("96 %", formatReading(96.0, "percent"))
        assertEquals("71 bpm", formatReading(71.0, "bpm"))
    }
}
