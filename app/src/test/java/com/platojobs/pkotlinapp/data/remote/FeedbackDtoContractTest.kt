package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.declaredMemberProperties

/** 反馈 DTO 与后端契约 0.12.0 的字段对齐：请求 context、响应 ID、列表不含隐私字段。 */
class FeedbackDtoContractTest {

    private val moshi = ApiClient.moshi()

    @Test
    fun `create request uses backend contract field names`() {
        val request = FeedbackCreateRequest(
            kind = FeedbackKind.BUG.wire,
            body = "崩溃了",
            contact = "me@example.com",
            context = FeedbackContextDto(
                appVersion = "0.19.0",
                buildType = "release",
                osVersion = "Android 14 (API 34)",
                deviceModel = "Pixel 8",
            ),
        )

        val json = moshi.adapter(FeedbackCreateRequest::class.java).toJson(request)
        listOf("kind", "body", "contact", "context", "platform", "appVersion", "buildType", "osVersion", "deviceModel")
            .forEach { key -> assertTrue(key, json.contains("\"$key\"")) }
        assertFalse(json.contains("\"diagnostics\""))
        assertEquals("bug", request.kind)
        assertEquals("android", request.context.platform)
    }

    @Test
    fun `created response exposes feedbackId and createdAt`() {
        val json = """{"feedbackId":"fb_123","createdAt":"2026-11-06T01:02:03Z"}"""
        val parsed = moshi.adapter(FeedbackCreatedDto::class.java).fromJson(json)!!
        assertEquals("fb_123", parsed.feedbackId)
        assertEquals("2026-11-06T01:02:03Z", parsed.createdAt)
    }

    @Test
    fun `page response parses items and ignores privacy fields absent from contract`() {
        // 即使后端意外多返回 contact/diagnostics，列表 DTO 也没有对应字段可用。
        val json = """
            {
              "items": [
                {
                  "feedbackId": "fb_1",
                  "kind": "feature",
                  "body": "希望支持导出",
                  "platform": "android",
                  "appVersion": "0.19.0",
                  "buildType": "debug",
                  "osVersion": "Android 14",
                  "deviceModel": "Pixel 8",
                  "status": "received",
                  "createdAt": "2026-11-05T10:00:00Z",
                  "contact": "leak@example.com",
                  "diagnostics": "secret"
                }
              ],
              "nextCursor": "cursor-1",
              "hasMore": true
            }
        """.trimIndent()
        val page = moshi.adapter(FeedbackPageResponse::class.java).fromJson(json)!!

        assertEquals(1, page.items?.size)
        assertEquals("fb_1", page.items!!.single().feedbackId)
        assertEquals("feature", page.items!!.single().kind)
        assertEquals("received", page.items!!.single().status)
        assertEquals("cursor-1", page.nextCursor)
        assertEquals(true, page.hasMore)

        val listFields = FeedbackItemDto::class.declaredMemberProperties.map { it.name }
        assertFalse(listFields.contains("contact"))
        assertFalse(listFields.contains("diagnostics"))
    }

    @Test
    fun `kind and status values align with backend enums`() {
        assertEquals("bug", FeedbackKind.BUG.wire)
        assertEquals("feature", FeedbackKind.FEATURE.wire)
        assertEquals("experience", FeedbackKind.EXPERIENCE.wire)
        assertEquals("other", FeedbackKind.OTHER.wire)
        // 旧本地分类读取时回落 other，不丢内容。
        assertEquals(FeedbackKind.OTHER, FeedbackKind.parse("CONNECTION"))
        assertEquals(FeedbackKind.OTHER, FeedbackKind.parse("SYNC"))
        assertEquals(FeedbackKind.BUG, FeedbackKind.parse("bug"))
        assertNotNull(FeedbackKind.parse(null))
    }
}
