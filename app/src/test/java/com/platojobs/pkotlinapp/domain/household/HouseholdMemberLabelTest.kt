package com.platojobs.pkotlinapp.domain.household

import org.junit.Assert.assertEquals
import org.junit.Test

/** 成员行展示名：有人名/昵称用人名，没有则用序号标签，绝不直接展示原始用户 ID。 */
class HouseholdMemberLabelTest {

    private fun ordinalLabel(ordinal: Int): String = "成员 $ordinal"

    @Test
    fun `uses server display name when present`() {
        assertEquals("张三", HouseholdMemberLabel.resolve("张三", 2, ::ordinalLabel))
    }

    @Test
    fun `trims surrounding whitespace before display`() {
        assertEquals("Alice", HouseholdMemberLabel.resolve("  Alice  ", 2, ::ordinalLabel))
    }

    @Test
    fun `falls back to ordinal label when name is missing`() {
        assertEquals("成员 2", HouseholdMemberLabel.resolve(null, 2, ::ordinalLabel))
    }

    @Test
    fun `blank name falls back to ordinal label`() {
        assertEquals("成员 3", HouseholdMemberLabel.resolve("   ", 3, ::ordinalLabel))
    }
}
