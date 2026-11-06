package com.platojobs.pkotlinapp.domain.scene

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4xx 终态 vs 网络/5xx 可重试的分类规则。 */
class SceneFailureTest {

    @Test
    fun `rejected 4xx is terminal`() {
        val failure = SceneFailure.of(SceneRejectedException("SCENE_ACTION_INVALID", "动作非法"))
        assertTrue(failure is SceneFailure.Terminal)
        failure as SceneFailure.Terminal
        assertEquals("SCENE_ACTION_INVALID", failure.code)
    }

    @Test
    fun `429 carries retry after`() {
        val failure = SceneFailure.of(SceneRetryableException(retryAfterSeconds = 30))
        assertTrue(failure is SceneFailure.Retryable)
        failure as SceneFailure.Retryable
        assertEquals(30L, failure.retryAfterSeconds)
    }

    @Test
    fun `io and unknown errors stay retryable`() {
        assertEquals(
            SceneFailure.Retryable(null),
            SceneFailure.of(IOException("offline")),
        )
        assertEquals(
            SceneFailure.Retryable(null),
            SceneFailure.of(IllegalStateException("5xx")),
        )
    }
}
