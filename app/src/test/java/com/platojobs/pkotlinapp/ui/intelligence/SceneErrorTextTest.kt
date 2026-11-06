package com.platojobs.pkotlinapp.ui.intelligence

import com.platojobs.pkotlinapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 后端场景错误码必须映射到可读文案，而不是落到「未知错误」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SceneErrorTextTest {

    @Test
    fun `each scene error code maps to distinct readable copy`() {
        val context = RuntimeEnvironment.getApplication()
        val codes = listOf(
            SceneErrorText.ERROR_NAME_INVALID,
            SceneErrorText.ERROR_COOLDOWN_OUT_OF_RANGE,
            SceneErrorText.ERROR_TRIGGER_INVALID,
            SceneErrorText.ERROR_ACTION_INVALID,
            SceneErrorText.ERROR_DEVICE_NOT_FOUND,
            SceneErrorText.ERROR_METRIC_NOT_SUPPORTED,
            SceneErrorText.ERROR_NOT_FOUND,
            SceneErrorText.ERROR_CONFLICT,
        )
        val texts = codes.map { SceneErrorText.of(context, it) }
        texts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals("每个错误码都应有独立文案", codes.size, texts.toSet().size)

        val unknown = SceneErrorText.of(context, "SOMETHING_ELSE")
        assertTrue(unknown.isNotBlank())
        assertNotEquals(SceneErrorText.of(context, null), "unknown")
        assertEquals(unknown, SceneErrorText.of(context, null))
    }

    @Test
    fun `resource ids are stable for both not-found and conflict aliases`() {
        assertEquals(
            R.string.scene_error_not_found,
            SceneErrorText.resourceOf("SCENE_NOT_FOUND"),
        )
        assertEquals(
            R.string.scene_error_version_conflict,
            SceneErrorText.resourceOf("SCENE_VERSION_CONFLICT"),
        )
    }
}
