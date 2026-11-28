package com.platojobs.pkotlinapp.data.repository

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 规则编辑必须只改内容，保留 id、启用状态、创建时间与触发历史；
 * 类型切换时旧类型的字段要清干净，避免指标规则残留到 RSSI 规则上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RuleRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RuleRepositoryImpl(db.ruleDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `editing rssi threshold keeps identity enabled state and trigger history`() = runBlocking {
        val id = repository.addRule(RuleType.LOW_RSSI, -70)
        repository.setEnabled(id, false)
        repository.markTriggered(id, 123L)
        val before = repository.getRules().single()

        repository.updateRule(id = id, type = RuleType.LOW_RSSI, threshold = -82)

        val after = repository.getRules().single()
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(-82, after.threshold)
        assertFalse(after.enabled)
        assertEquals(123L, after.lastTriggeredTimestamp)
    }

    @Test
    fun `switching metric rule to rssi clears metric fields`() = runBlocking {
        val id = repository.addMetricRule(
            metric = "temperature",
            comparator = RuleComparator.LESS_OR_EQUAL,
            thresholdValue = 18.5,
            consecutive = 3,
        )

        repository.updateRule(id = id, type = RuleType.LOW_RSSI, threshold = -75)

        val rule = repository.getRules().single()
        assertEquals(RuleType.LOW_RSSI, rule.type)
        assertEquals(-75, rule.threshold)
        assertNull(rule.metric)
        assertEquals(RuleComparator.GREATER, rule.comparator)
        assertEquals(0.0, rule.thresholdValue, 0.0)
        assertEquals(1, rule.consecutive)
    }

    @Test
    fun `switching rssi rule to metric writes metric parameters`() = runBlocking {
        val id = repository.addRule(RuleType.LOW_RSSI, -80)

        repository.updateRule(
            id = id,
            type = RuleType.METRIC_THRESHOLD,
            threshold = 0,
            metric = "humidity",
            comparator = RuleComparator.LESS,
            thresholdValue = 35.0,
            consecutive = 9,
        )

        val rule = repository.getRules().single()
        assertEquals(RuleType.METRIC_THRESHOLD, rule.type)
        assertEquals("humidity", rule.metric)
        assertEquals(RuleComparator.LESS, rule.comparator)
        assertEquals(35.0, rule.thresholdValue, 0.0)
        // 连续条数按契约保留合法值（1–30）。
        assertEquals(9, rule.consecutive)
        assertTrue(repository.observeRules().first().single().enabled)
    }
}
