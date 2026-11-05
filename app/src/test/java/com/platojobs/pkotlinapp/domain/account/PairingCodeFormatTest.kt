package com.platojobs.pkotlinapp.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeFormatTest {

    @Test
    fun `小写会自动转大写`() {
        assertEquals("ABCDEFGH", PairingCodeFormat.sanitize("abcdefgh"))
    }

    @Test
    fun `粘贴带分隔符的码会被清洗且只保留 8 位`() {
        assertEquals("K7M2QX9P", PairingCodeFormat.sanitize(" k7m2-qx9p "))
    }

    @Test
    fun `过滤易混字符 0 O 1 I`() {
        // 0/O/1/I 会被丢弃，其余字符按顺序前移。
        assertEquals("ABCDEFGH", PairingCodeFormat.sanitize("O0I1ABCDEFGH"))
        assertEquals("", PairingCodeFormat.sanitize("O01I"))
    }

    @Test
    fun `超长输入截断到 8 位`() {
        assertEquals("ABCDEFGH", PairingCodeFormat.sanitize("ABCDEFGHJK"))
    }

    @Test
    fun `空输入与 null 都得到空串`() {
        assertEquals("", PairingCodeFormat.sanitize(null))
        assertEquals("", PairingCodeFormat.sanitize(""))
    }

    @Test
    fun `isValid 只接受 8 位允许字符`() {
        assertTrue(PairingCodeFormat.isValid("ABCD2345"))
        assertFalse(PairingCodeFormat.isValid("ABCD234"))
        assertFalse(PairingCodeFormat.isValid("ABCD23456"))
        assertFalse(PairingCodeFormat.isValid("ABCD2O45"))
        assertFalse(PairingCodeFormat.isValid(null))
    }
}
