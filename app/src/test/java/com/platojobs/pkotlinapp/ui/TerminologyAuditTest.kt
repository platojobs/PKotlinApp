package com.platojobs.pkotlinapp.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 术语审计：按设计规范第 8 节，设备/装置、同步/上传、读数/数值等易混词只保留前者。
 * 直接解析 strings.xml，确保高频文案不会在后续改动中回退。
 */
class TerminologyAuditTest {

    private val bannedTerms = listOf("装置", "机器", "上传", "策略", "参数", "最后在线", "数值")

    private val stringValues: Map<String, String> by lazy {
        val file = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml 不存在：${file.absolutePath}", file.exists())
        val regex = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        regex.findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    @Test
    fun `高频文案不含禁用术语`() {
        val audited = listOf(
            "action_scan",
            "home_devices_empty_hint",
            "home_devices_empty_action",
            "profile_sync_pending",
            "profile_sync_success",
            "readings_title",
            "readings_empty_hint",
            "onboarding_result_local_only",
            "onboarding_step_location_body_legacy",
        )
        assertTrue("审计样本少于 5 条", audited.size >= 5)
        audited.forEach { name ->
            val value = stringValues[name]
            assertTrue("缺少高频字符串：$name", value != null)
            bannedTerms.forEach { banned ->
                assertFalse("「$name」含禁用术语「$banned」：$value", value!!.contains(banned))
            }
        }
    }

    @Test
    fun `同步异常文案按 410 401 503 分开`() {
        val resynced = stringValues.getValue("profile_sync_resynced")
        assertTrue(resynced.contains("云端变更记录已过期"))
        assertTrue(resynced.contains("已重新全量同步"))

        assertTrue(stringValues.getValue("profile_sync_error_busy").contains("服务端繁忙"))
        assertTrue(stringValues.getValue("profile_sync_error_auth").contains("登录已失效"))
        assertTrue(stringValues.getValue("profile_session_expires").contains("会话到期"))
    }

    @Test
    fun `扫描与同步使用统一动词`() {
        assertTrue(stringValues.getValue("action_scan").contains("扫描"))
        assertTrue(stringValues.getValue("profile_sync_pending").startsWith("待同步"))
        assertTrue(stringValues.getValue("readings_title") == "读数")
    }
}
