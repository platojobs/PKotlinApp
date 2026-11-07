package com.platojobs.pkotlinapp.e2e

import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.FeedbackContextDto
import com.platojobs.pkotlinapp.data.remote.FeedbackCreateRequest
import com.platojobs.pkotlinapp.data.remote.NullableReported
import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.PushReadingDto
import com.platojobs.pkotlinapp.data.remote.ReadingMetricsResponse
import com.platojobs.pkotlinapp.data.remote.ReadingPageResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSeriesResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSummaryResponse
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SceneActionDto
import com.platojobs.pkotlinapp.data.remote.SceneBoolFieldDto
import com.platojobs.pkotlinapp.data.remote.SceneDeviceRefDto
import com.platojobs.pkotlinapp.data.remote.SceneInputDto
import com.platojobs.pkotlinapp.data.remote.ScenePatchDto
import com.platojobs.pkotlinapp.data.remote.SceneStringFieldDto
import com.platojobs.pkotlinapp.data.remote.SceneTimestamps
import com.platojobs.pkotlinapp.data.remote.SceneTriggerDto
import com.platojobs.pkotlinapp.data.remote.SyncChangeDto
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncPushResponse
import com.platojobs.pkotlinapp.data.remote.SyncScanRecordDto
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.scene.RemoteSceneDataSource
import com.platojobs.pkotlinapp.data.sync.RemoteSyncDataSource
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.data.sync.toDomain
import com.platojobs.pkotlinapp.data.sync.toPushDto
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 对本机 Go 后端（PJGoIoT，STORE=fixture）执行端到端契约联调。
 *
 * 默认不参与 `testDebugUnitTest`，通过 `./gradlew :app:e2eContract` 或
 * `./scripts/e2e-contract.sh` 运行。服务地址由 `e2eBaseUrl` 系统属性 / `E2E_BASE_URL` 环境变量指定。
 */
class E2eContractTest {

    private val baseUrl = (
        System.getProperty("e2eBaseUrl")
            ?: System.getenv("E2E_BASE_URL")
            ?: "http://127.0.0.1:8080"
        ).trimEnd('/')

    /** fixture / postgres；由 Gradle 任务或环境变量注入，决定额外契约用例是否执行。 */
    private val store = (
        System.getProperty("e2eStore")
            ?: System.getenv("E2E_STORE")
            ?: "fixture"
        ).lowercase()
    private val apiBase = "$baseUrl/api/v1"

    /**
     * 反馈限流专属 fixture（`FEEDBACK_USER_RATE_PER_MINUTE=3`、burst=3）：
     * 脚本另起一个实例，避免把常规 e2e 的多次提交打挂。
     */
    private val feedbackLimitBaseUrl = (
        System.getProperty("e2eFeedbackLimitBaseUrl")
            ?: System.getenv("E2E_FEEDBACK_LIMIT_BASE_URL")
            ?: ""
        ).trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 复用生产 Moshi（含 Reported 三态 adapter），避免测试与真实序列化行为漂移。
    private val moshi = ApiClient.moshi()

    private val syncApi: SyncApi = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SyncApi::class.java)

    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java,
    )
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val pushRequestAdapter = moshi.adapter(SyncPushRequest::class.java)
    private val pushResponseAdapter = moshi.adapter(SyncPushResponse::class.java)
    private val changesResponseAdapter = moshi.adapter(SyncChangesResponse::class.java)
    private val readingPageAdapter = moshi.adapter(ReadingPageResponse::class.java)
    private val readingSummaryAdapter = moshi.adapter(ReadingSummaryResponse::class.java)
    private val readingSeriesAdapter = moshi.adapter(ReadingSeriesResponse::class.java)
    private val readingMetricsAdapter = moshi.adapter(ReadingMetricsResponse::class.java)
    private val feedbackCreateAdapter = moshi.adapter(FeedbackCreateRequest::class.java)

    @Test
    fun `contract against local go backend`() {
        println("[E2E] baseUrl=$baseUrl")

        step("health 200 + store=$store") {
            val result = get("/health")
            assertEquals(200, result.code)
            val health = parseMap(result.body)
            assertEquals("ok", health["status"])
            val actualStore = health["store"] as? String ?: error("missing store")
            assertEquals(store, actualStore)
            assertNotNull(health["version"])
        }

        val user = captureStep("anonymous login returns userId + token") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            val userId = body["userId"] as? String ?: error("missing userId")
            val token = body["token"] as? String ?: error("missing token")
            runCatching { UUID.fromString(userId) }
                .onFailure { error("userId is not UUID: $userId") }
            assertTrue(token.isNotBlank())
            assertTokenTtl(body, "anonymous login")
            UserSession(userId, token)
        }

        step("missing token -> 401 UNAUTHORIZED") {
            assertUnauthorized(get("/sync/changes?cursor=0"))
            assertUnauthorized(post("/sync/push", "{}"))
        }

        val now = System.currentTimeMillis()
        val baseUpdatedAt = now - 10_000
        val createdAt = now - 10_000
        // 0.20.0 markedAt：带微秒精度，验证服务端持久化/回读不做毫秒截断。
        val markAtMicros = Rfc3339.format(now - 5_000).dropLast(1) + "456Z"
        val androidPush = PushDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = "android",
            externalId = "AA:BB:CC:00:00:01",
            mac = "aa:bb:cc:00:00:01",
            // 本地/catalog 能匹配到型号时才上报。
            deviceType = "nordic_uart",
            alias = Reported("共享型号-Android"),
            advertisedName = Reported("PJ-Sensor"),
            group = Reported("联调"),
            markedAt = NullableReported(markAtMicros),
            scanRecords = listOf(
                SyncScanRecordDto(rssi = -55, at = Rfc3339.format(createdAt)),
            ),
            updatedAt = Rfc3339.format(baseUpdatedAt),
        )
        val iosPush = PushDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = "ios",
            externalId = "E621E1F8-C36C-495A-93FC-0C247A3E6E5F",
            alias = Reported("共享型号-iOS"),
            group = Reported("联调"),
            scanRecords = emptyList(),
            updatedAt = Rfc3339.format(baseUpdatedAt),
        )
        val pushRequest = SyncPushRequest(
            clientId = "e2e-android-client",
            devices = listOf(androidPush, iosPush),
        )
        val pushJson = pushRequestAdapter.toJson(pushRequest)
        // 可选 deviceType：匹配到时序列化，未匹配时不写字段（依赖服务端沿用旧值）。
        assertTrue(pushJson.contains("\"deviceType\":\"nordic_uart\""))
        assertFalse(pushJson.contains("\"deviceType\":null"))
        // 0.3.3：别名与广播名独立上报，两者都带上时互不覆盖。
        assertTrue(pushJson.contains("\"alias\":\"共享型号-Android\""))
        assertTrue(pushJson.contains("\"advertisedName\":\"PJ-Sensor\""))
        assertTrue(pushJson.contains("\"group\":\"联调\""))
        // markedAt 只有显式上报时才出现；这里的微秒精度必须原样带上。
        assertTrue(pushJson.contains("\"markedAt\":\"$markAtMicros\""))
        assertFalse(pushJson.contains("\"markedAt\":null"))
        assertFalse(
            pushRequestAdapter.toJson(
                SyncPushRequest(clientId = "e2e-android-client", devices = listOf(iosPush)),
            ).contains("\"deviceType\""),
        )
        val firstKey = "e2e-${UUID.randomUUID()}"

        val firstPush = captureStep("push two devices (android + ios)") {
            val result = push(pushJson, user.token, firstKey)
            assertEquals(200, result.code)
            val response = pushResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals(2, response.applied.orEmpty().size)
            assertTrue(response.conflicts.orEmpty().isEmpty())
            assertTrue(response.nextCursor != null && response.nextCursor!! > 0)
            val acceptedKeys = response.applied.orEmpty()
                .map { "${it.platform}:${it.externalId}" }
                .toSet()
            assertEquals(
                setOf(
                    "android:AA:BB:CC:00:00:01",
                    "ios:E621E1F8-C36C-495A-93FC-0C247A3E6E5F",
                ),
                acceptedKeys,
            )
            response
        }
        val cursorAfterPush = firstPush.nextCursor!!.toString()

        step("my device lookup uses compound platform + externalId") {
            val result = get("/my/devices/android/AA:BB:CC:00:00:01", user.token)
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals("android", body["platform"])
            assertEquals("AA:BB:CC:00:00:01", body["externalId"])
            assertEquals("共享型号-Android", body["alias"])
            assertEquals("PJ-Sensor", body["advertisedName"])
            // 回读必须与推送一致，且微秒精度不丢（不是截断到毫秒）。
            assertEquals(markAtMicros, body["markedAt"])
            // 0.3.2 enrich：型号、厂商、固件、兼容性均由云端返回。
            assertEquals("nordic_uart", body["deviceType"])
            assertEquals("Nordic Semiconductor", body["vendor"])
            assertEquals("nRF Connect SDK v2.9.0", body["firmwareVersion"])
            val compatibility = body["compatibility"] as? Map<*, *>
                ?: error("missing compatibility")
            assertEquals(listOf("android", "ios"), compatibility["platforms"])
            assertEquals("ble-gatt", compatibility["protocol"])
        }

        val changes = captureStep("changes returns contract-shaped payload + platform/externalId") {
            val result = get("/sync/changes?cursor=0", user.token)
            assertEquals(200, result.code)
            val response = changesResponseAdapter.fromJson(result.body) ?: error("empty body")
            val list = response.changes.orEmpty()
            assertEquals(2, list.size)
            assertFalse(response.hasMore ?: false)
            assertEquals(list.maxOf { it.seq!! }, response.nextCursor!!)

            val androidChange = list.single { it.payload?.platform == "android" }
            val iosChange = list.single { it.payload?.platform == "ios" }

            list.forEach { change ->
                assertTrue(change.seq!! > 0)
                assertEquals("user_device", change.entity)
                assertEquals("upsert", change.op)
                assertTrue(change.entityId!!.contains(user.userId))
                assertNotNull(Rfc3339.parse(change.serverTs))
                assertNotNull(change.payload?.updatedAt)
            }

            val androidPayload = androidChange.payload!!
            val android = androidPayload.toDomain()!!
            assertEquals("AA:BB:CC:00:00:01", android.externalId)
            assertEquals("共享型号-Android", android.name)
            assertEquals("PJ-Sensor", android.advertisedName)
            assertEquals("联调", android.group)
            assertEquals(-55, android.lastRssi)
            assertEquals(1, androidPayload.scanRecords.orEmpty().size)
            assertEquals(baseUpdatedAt, android.updatedAt)
            assertEquals("android", android.platform)
            // entity=user_device 的 payload 必须带 markedAt，值与 push 一致。
            assertNotNull(androidPayload.markedAt)
            assertEquals(markAtMicros, androidPayload.markedAt?.value)

            val ios = iosChange.payload!!.toDomain()!!
            assertEquals("E621E1F8-C36C-495A-93FC-0C247A3E6E5F", ios.externalId)
            assertEquals("共享型号-iOS", ios.name)
            assertEquals("ios", ios.platform)
            assertTrue(iosChange.payload.mac.isNullOrBlank())

            response
        }
        assertEquals(cursorAfterPush, changes.nextCursor!!.toString())

        step("same Idempotency-Key + same body replays without new changes") {
            val replay = push(pushJson, user.token, firstKey)
            assertEquals(200, replay.code)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            val replayBody = pushResponseAdapter.fromJson(replay.body) ?: error("empty body")
            assertEquals(firstPush.nextCursor, replayBody.nextCursor)
            assertEquals(
                firstPush.applied.orEmpty().map { it.serverSeq },
                replayBody.applied.orEmpty().map { it.serverSeq },
            )

            val after = changesResponseAdapter.fromJson(
                get("/sync/changes?cursor=$cursorAfterPush", user.token).body,
            )!!
            assertTrue(after.changes.orEmpty().isEmpty())
            assertEquals(cursorAfterPush.toLong(), after.nextCursor!!)
        }

        step("LWW conflict loses to server and is reported, not overwritten") {
            // 可缺省字段故意省略（advertisedName/group 不传），验证冲突响应按 absent 解析。
            val oldAndroid = androidPush.copy(
                alias = Reported("旧名称-冲突"),
                advertisedName = null,
                group = null,
                // 冲突请求里不报 markedAt：服务端记录里的标记不能被误清。
                markedAt = null,
                updatedAt = Rfc3339.format(now - 120_000),
            )
            val conflictJson = pushRequestAdapter.toJson(
                SyncPushRequest(clientId = "e2e-android-client", devices = listOf(oldAndroid)),
            )
            val result = push(conflictJson, user.token, "e2e-conflict-${UUID.randomUUID()}")
            assertEquals(200, result.code)
            val response = pushResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertTrue(response.applied.orEmpty().isEmpty())
            assertEquals(1, response.conflicts.orEmpty().size)
            val conflict = response.conflicts!!.single()
            assertEquals("android", conflict.platform)
            assertEquals("AA:BB:CC:00:00:01", conflict.externalId)
            assertEquals("server_newer", conflict.reason)
            assertEquals("共享型号-Android", conflict.server?.alias)
            assertEquals("PJ-Sensor", conflict.server?.advertisedName)
            assertEquals("联调", conflict.server?.group?.value)
            assertEquals("旧名称-冲突", conflict.client?.alias?.value)
            // 请求里未上报的可缺省字段，解析回来必须仍是 absent，而不是空串。
            assertNull(conflict.client?.advertisedName)
            assertNull(conflict.client?.group)
            // 服务端会回显 client 记录并补齐 markedAt（未标记为 null，而不是省略）。
            assertNull(conflict.client?.markedAt?.value)
            // 服务端记录里的 markedAt 原样回给客户端，冲突不覆盖本地新值。
            assertEquals(markAtMicros, conflict.server?.markedAt?.value)

            // 冲突未落库：服务端仍是新名称，且没有产生新的 change。
            val myDevice = parseMap(
                get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body,
            )
            assertEquals("共享型号-Android", myDevice["alias"])
            assertEquals(markAtMicros, myDevice["markedAt"])
            val after = changesResponseAdapter.fromJson(
                get("/sync/changes?cursor=$cursorAfterPush", user.token).body,
            )!!
            assertTrue(after.changes.orEmpty().isEmpty())
        }

        step("same Idempotency-Key with different body -> 409 IDEMPOTENCY_CONFLICT") {
            val result = push(
                pushRequestAdapter.toJson(
                    SyncPushRequest(clientId = "e2e-android-client", devices = listOf(iosPush)),
                ),
                user.token,
                firstKey,
            )
            assertEquals(409, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("IDEMPOTENCY_CONFLICT", error["code"])
        }

        step("scan-only push updates advertisedName without touching alias or group") {
            val scanned = androidPush.copy(
                alias = null,
                advertisedName = Reported("PJ-Sensor-2"),
                group = null,
                updatedAt = Rfc3339.format(now + 15_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(scanned)),
            )
            assertFalse(json.contains("\"alias\""))
            assertFalse(json.contains("\"group\""))
            assertTrue(json.contains("\"advertisedName\":\"PJ-Sensor-2\""))

            assertEquals(
                200,
                push(json, user.token, "e2e-scan-${UUID.randomUUID()}").code,
            )
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("共享型号-Android", body["alias"])
            assertEquals("PJ-Sensor-2", body["advertisedName"])
            assertEquals("联调", body["group"])
        }

        step("alias edit only updates alias without touching advertisedName or group") {
            val aliasEdited = androidPush.copy(
                alias = Reported("改名后-Android"),
                advertisedName = null,
                group = null,
                updatedAt = Rfc3339.format(now + 20_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(aliasEdited)),
            )
            assertFalse(json.contains("\"advertisedName\""))
            assertFalse(json.contains("\"group\""))

            assertEquals(
                200,
                push(json, user.token, "e2e-alias-${UUID.randomUUID()}").code,
            )
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("改名后-Android", body["alias"])
            assertEquals("PJ-Sensor-2", body["advertisedName"])
            assertEquals("联调", body["group"])
        }

        step("clearing group sends explicit empty string, not absent") {
            val cleared = androidPush.copy(
                alias = null,
                advertisedName = null,
                group = Reported(""),
                updatedAt = Rfc3339.format(now + 25_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(cleared)),
            )
            // 三态：显式空串必须出现在请求体里；未上报字段则必须完全省略。
            assertTrue(json.contains("\"group\":\"\""))
            assertFalse(json.contains("\"alias\""))

            assertEquals(200, push(json, user.token, "e2e-clear-${UUID.randomUUID()}").code)
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("", body["group"])
            // 上一次编辑的别名/广播名保持，不被本次清除动作影响。
            assertEquals("改名后-Android", body["alias"])
            assertEquals("PJ-Sensor-2", body["advertisedName"])
        }

        step("subsequent sync without group does not revive the cleared group") {
            val nextScan = androidPush.copy(
                alias = null,
                advertisedName = Reported("PJ-Sensor-3"),
                group = null,
                // 后续扫描不上报 markedAt：服务端必须保持原标记。
                markedAt = null,
                updatedAt = Rfc3339.format(now + 30_000),
            )
            assertEquals(
                200,
                push(
                    pushRequestAdapter.toJson(
                        SyncPushRequest("e2e-android-client", listOf(nextScan)),
                    ),
                    user.token,
                    "e2e-no-revive-${UUID.randomUUID()}",
                ).code,
            )
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("", body["group"])
            assertEquals("PJ-Sensor-3", body["advertisedName"])
            assertEquals(markAtMicros, body["markedAt"])
        }

        step("clearing alias sends explicit empty string and keeps advertisedName") {
            val cleared = androidPush.copy(
                alias = Reported(""),
                advertisedName = null,
                group = null,
                markedAt = null,
                updatedAt = Rfc3339.format(now + 35_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(cleared)),
            )
            // 显式清空别名必须出现在请求体；广播名与未修改的分组保持 absent。
            assertTrue(json.contains("\"alias\":\"\""))
            assertFalse(json.contains("\"advertisedName\""))
            assertFalse(json.contains("\"group\""))

            assertEquals(200, push(json, user.token, "e2e-alias-clear-${UUID.randomUUID()}").code)
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("", body["alias"])
            assertEquals("PJ-Sensor-3", body["advertisedName"])
            assertEquals("", body["group"])
            assertEquals(markAtMicros, body["markedAt"])
        }

        step("scan after alias clear does not revive the old alias") {
            val scan = androidPush.copy(
                alias = null,
                advertisedName = Reported("PJ-Sensor-4"),
                group = null,
                markedAt = null,
                updatedAt = Rfc3339.format(now + 40_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(scan)),
            )
            assertFalse(json.contains("\"alias\""))
            assertFalse(json.contains("\"markedAt\""))

            assertEquals(
                200,
                push(json, user.token, "e2e-alias-no-revive-${UUID.randomUUID()}").code,
            )
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("", body["alias"])
            assertEquals("PJ-Sensor-4", body["advertisedName"])
            assertEquals("", body["group"])
            assertEquals(markAtMicros, body["markedAt"])

            // 显式 null 取消标记：回读为 null，并且 change_log 新增一条 payload 含 markedAt 的记录。
            val before = changesResponseAdapter.fromJson(
                get("/sync/changes?cursor=0", user.token).body,
            )!!
            val beforeCount = before.changes.orEmpty().size
            val cancel = androidPush.copy(
                alias = null,
                advertisedName = null,
                group = null,
                markedAt = NullableReported(null),
                updatedAt = Rfc3339.format(now + 45_000),
            )
            val cancelJson = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(cancel)),
            )
            // Moshi 默认会吞掉 name 后的 null；取消标记必须真的写出 "markedAt":null。
            assertTrue(cancelJson.contains("\"markedAt\":null"))
            assertEquals(
                200,
                push(cancelJson, user.token, "e2e-mark-clear-${UUID.randomUUID()}").code,
            )
            val cleared = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertNull(cleared["markedAt"])
            assertEquals("", cleared["alias"])
            assertEquals("PJ-Sensor-4", cleared["advertisedName"])
            assertEquals("", cleared["group"])

            val after = changesResponseAdapter.fromJson(
                get("/sync/changes?cursor=0", user.token).body,
            )!!
            assertEquals(beforeCount + 1, after.changes.orEmpty().size)
            val latest = after.changes.orEmpty().maxByOrNull { it.seq ?: 0 }!!
            assertEquals("user_device", latest.entity)
            assertEquals("upsert", latest.op)
            assertNotNull(latest.payload?.markedAt)
            assertNull(latest.payload?.markedAt?.value)
        }

        step("cross-user access -> 404 NOT_FOUND") {
            val other = post("/auth/anonymous").let { result ->
                assertEquals(201, result.code)
                val body = parseMap(result.body)
                UserSession(
                    body["userId"] as String,
                    body["token"] as String,
                )
            }
            val result = get("/my/devices/android/AA:BB:CC:00:00:01", other.token)
            assertEquals(404, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("NOT_FOUND", error["code"])

            // 变更日志也按用户隔离。
            val otherChanges = changesResponseAdapter.fromJson(
                get("/sync/changes?cursor=0", other.token).body,
            )!!
            assertTrue(otherChanges.changes.orEmpty().isEmpty())
        }

        val unregisteredId = UUID.randomUUID().toString()
        val unregisteredExternalId = "AA:BB:CC:00:00:99"
        val unregistered = PushDeviceDto(
            id = unregisteredId,
            platform = "android",
            externalId = unregisteredExternalId,
            mac = unregisteredExternalId.lowercase(),
            // 未注册型号：不猜 deviceType，保持 null。
            alias = Reported("未注册型号-Android"),
            group = Reported("未知型号"),
            scanRecords = listOf(
                SyncScanRecordDto(rssi = -70, at = Rfc3339.format(now)),
            ),
            updatedAt = Rfc3339.format(now),
        )
        step("unregistered model push -> enrich fields are null, alias/group kept") {
            val result = push(
                pushRequestAdapter.toJson(
                    SyncPushRequest("e2e-android-client", listOf(unregistered)),
                ),
                user.token,
                "e2e-unregistered-${UUID.randomUUID()}",
            )
            assertEquals(200, result.code)

            val lookup = get("/my/devices/android/$unregisteredExternalId", user.token)
            assertEquals(200, lookup.code)
            val body = parseMap(lookup.body)
            assertNull(body["deviceType"])
            assertNull(body["vendor"])
            assertNull(body["firmwareVersion"])
            assertNull(body["compatibility"])
            assertEquals("未注册型号-Android", body["alias"])
            assertEquals("未知型号", body["group"])
            assertNotNull(body["updatedAt"])
        }

        step("optional deviceType accepted when matched, firmware stays null instead of fake") {
            val marked = unregistered.copy(
                deviceType = "environmental_sensor",
                alias = Reported("已标记型号"),
                updatedAt = Rfc3339.format(now + 5_000),
            )
            val result = push(
                pushRequestAdapter.toJson(
                    SyncPushRequest("e2e-android-client", listOf(marked)),
                ),
                user.token,
                "e2e-marked-${UUID.randomUUID()}",
            )
            assertEquals(200, result.code)

            val body = parseMap(
                get("/my/devices/android/$unregisteredExternalId", user.token).body,
            )
            assertEquals("environmental_sensor", body["deviceType"])
            assertEquals("Bluetooth SIG", body["vendor"])
            // 公共库没有该 MAC 的固件记录，不能伪造版本。
            assertNull(body["firmwareVersion"])
            assertNotNull(body["compatibility"] as? Map<*, *>)
        }

        step("push without deviceType keeps server value, does not clear it") {
            val aliasOnly = unregistered.copy(
                deviceType = null,
                alias = Reported("仅改别名"),
                group = null,
                updatedAt = Rfc3339.format(now + 10_000),
            )
            val result = push(
                pushRequestAdapter.toJson(
                    SyncPushRequest("e2e-android-client", listOf(aliasOnly)),
                ),
                user.token,
                "e2e-retain-${UUID.randomUUID()}",
            )
            assertEquals(200, result.code)

            val body = parseMap(
                get("/my/devices/android/$unregisteredExternalId", user.token).body,
            )
            assertEquals("仅改别名", body["alias"])
            // 本次未上报 group：服务端必须保留旧值，不能被空值覆盖。
            assertEquals("未知型号", body["group"])
            assertEquals("environmental_sensor", body["deviceType"])
            assertEquals("Bluetooth SIG", body["vendor"])
        }

        step("Retrofit SyncApi anonymous login parses production DTO") {
            val login = runBlocking { syncApi.anonymousLogin() }
            val token = login.token?.takeIf { it.isNotBlank() } ?: error("missing token")
            assertNotNull(login.userId)
            assertTrue(token.isNotBlank())
            val response = runBlocking { syncApi.changes("Bearer $token", "0") }
            assertTrue(response.changes.orEmpty().isEmpty())
        }

        step("Retrofit SyncApi push uses production mapping") {
            val pushedAt = System.currentTimeMillis()
            val record = SyncRecord(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = "AA:BB:CC:00:00:02",
                name = "契约联调-生产映射",
                lastRssi = -60,
                lastSeenTimestamp = pushedAt,
                updatedAt = pushedAt,
            )
            val response = runBlocking {
                syncApi.push(
                    "Bearer ${user.token}",
                    "e2e-prod-${UUID.randomUUID()}",
                    SyncPushRequest(
                        clientId = "e2e-android-client",
                        devices = listOf(record.toPushDto()),
                    ),
                )
            }
            assertEquals(1, response.applied.orEmpty().size)
            assertEquals("AA:BB:CC:00:00:02", response.applied.orEmpty().single().externalId)
        }

        step("device-types catalog returns deviceType + firmwares + accessories") {
            val types = parseMap(get("/device-types").body)
            val items = types["items"] as? List<*> ?: error("missing items")
            assertTrue(items.isNotEmpty())

            // 选一个 fixture 中同时有固件与配件的类型，避免依赖列表顺序。
            val type = items.mapNotNull { (it as? Map<*, *>)?.get("type") as? String }
                .firstOrNull { it == "environmental_sensor" }
                ?: error("missing environmental_sensor fixture")
            val catalog = parseMap(get("/device-types/$type/catalog").body)

            val deviceType = catalog["deviceType"] as? Map<*, *> ?: error("missing deviceType")
            assertEquals(type, deviceType["type"])

            val firmwares = catalog["firmwares"] as? List<*> ?: error("missing firmwares")
            assertTrue(firmwares.isNotEmpty())
            firmwares.forEach { raw ->
                val firmware = raw as? Map<*, *> ?: error("bad firmware")
                // 0.3.2：固件带 vendor 与 compatibility，供市场做真实维度筛选。
                assertTrue(firmware["vendor"] is String)
                assertNotNull(firmware["compatibility"] as? Map<*, *>)
            }

            val accessories = catalog["accessories"] as? List<*> ?: error("missing accessories")
            assertTrue(accessories.isNotEmpty())
            accessories.forEach { raw ->
                val accessory = raw as? Map<*, *> ?: error("bad accessory")
                assertTrue(accessory["vendor"] is String)
                assertNotNull(accessory["compatibility"] as? Map<*, *>)
            }
            // 未注册型号的 catalog 请求应返回 404，而不是 500。
            assertEquals(404, get("/device-types/not_registered/catalog").code)
        }

        println("[E2E] SUMMARY: all contract cases passed")
    }

    /**
     * 读数契约：推送（applied/skipped）、按 id 幂等、倒序分页查询、metric 过滤、
     * 24h 汇总，以及 NaN 值必须被 400 拒绝。
     */
    @Test
    fun `readings contract against local go backend`() {
        val user = captureStep("readings: anonymous login") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            assertTokenTtl(body, "readings anonymous login")
            UserSession(
                userId = body["userId"] as? String ?: error("missing userId"),
                token = body["token"] as? String ?: error("missing token"),
            )
        }
        val externalId = "AA:BB:CC:00:00:02"
        val now = System.currentTimeMillis()
        val readings = listOf(
            PushReadingDto(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = externalId,
                metric = "temperature",
                value = 21.5,
                unit = "°C",
                recordedAt = Rfc3339.format(now - 60_000),
            ),
            PushReadingDto(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = externalId,
                metric = "temperature",
                value = 22.0,
                unit = "°C",
                recordedAt = Rfc3339.format(now - 30_000),
            ),
            PushReadingDto(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = externalId,
                metric = "heart_rate",
                value = 72.0,
                unit = "bpm",
                recordedAt = Rfc3339.format(now - 45_000),
            ),
        )
        val request = SyncPushRequest(
            clientId = "e2e-readings-client",
            // 查询/汇总要求设备已存在；跟随设备一起推送，模拟真实同步顺序。
            devices = listOf(
                PushDeviceDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = externalId,
                    mac = externalId.lowercase(),
                    deviceType = "nordic_uart",
                    updatedAt = Rfc3339.format(now),
                ),
            ),
            readings = readings,
        )
        val json = pushRequestAdapter.toJson(request)
        assertTrue(json.contains("\"readings\""))
        assertFalse(json.contains("\"devices\":null"))

        val applied = captureStep("readings: push three readings") {
            val result = push(json, user.token, "e2e-readings-${UUID.randomUUID()}")
            assertEquals(200, result.code)
            val response = pushResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals(3, response.appliedReadings)
            assertEquals(0, response.skippedReadings)
            response
        }
        assertEquals(3, applied.appliedReadings)

        step("readings: duplicate ids are skipped, not errors") {
            // 换一个 Idempotency-Key 重放同一批 id，验证服务端按 id 幂等。
            val replay = push(json, user.token, "e2e-readings-${UUID.randomUUID()}")
            assertEquals(200, replay.code)
            val response = pushResponseAdapter.fromJson(replay.body) ?: error("empty body")
            assertEquals(0, response.appliedReadings)
            assertEquals(3, response.skippedReadings)
        }

        // fixture 后端只做 DerivePresence、不维护 last_seen_at（真实值由 postgres 实跑覆盖），
        // 这里校验契约字段存在且 unknown 是独立一档、lastSeenAt 为 null。
        step("readings: presence field is exposed and unknown stays unknown") {
            val backfillExternalId = "AA:BB:CC:00:00:08"
            val neverExternalId = "AA:BB:CC:00:00:09"
            // 补录 2 小时前的旧读数：presence 只按服务端接收时间推导，不看 recordedAt。
            val presenceRequest = SyncPushRequest(
                clientId = "e2e-presence-client",
                devices = listOf(
                    PushDeviceDto(
                        id = UUID.randomUUID().toString(),
                        platform = "android",
                        externalId = backfillExternalId,
                        mac = backfillExternalId.lowercase(),
                        updatedAt = Rfc3339.format(now),
                    ),
                    PushDeviceDto(
                        id = UUID.randomUUID().toString(),
                        platform = "android",
                        externalId = neverExternalId,
                        mac = neverExternalId.lowercase(),
                        updatedAt = Rfc3339.format(now),
                    ),
                ),
                readings = listOf(
                    PushReadingDto(
                        id = UUID.randomUUID().toString(),
                        platform = "android",
                        externalId = backfillExternalId,
                        metric = "temperature",
                        value = 20.0,
                        unit = "°C",
                        recordedAt = Rfc3339.format(now - 2 * 3_600_000L),
                    ),
                ),
            )
            val pushed = push(
                pushRequestAdapter.toJson(presenceRequest),
                user.token,
                "e2e-presence-${UUID.randomUUID()}",
            )
            assertEquals(200, pushed.code)

            val list = get("/my/devices", user.token)
            assertEquals(200, list.code)
            val devices = (parseMap(list.body)["items"] as? List<*>).orEmpty()
                .map { it as? Map<*, *> }

            val reported = devices.firstOrNull { it?.get("externalId") == externalId }
                ?: error("missing reported device")
            assertTrue(
                "presence must be present in payload",
                reported["presence"] in setOf("unknown", "online", "offline"),
            )

            val backfilled = devices.firstOrNull { it?.get("externalId") == backfillExternalId }
                ?: error("missing backfill device")
            assertTrue(
                "backfill presence must be present in payload",
                backfilled["presence"] in setOf("unknown", "online", "offline"),
            )

            // 从未上报：unknown 是独立一档，不能并进 offline；lastSeenAt 必须为 null。
            val never = devices.firstOrNull { it?.get("externalId") == neverExternalId }
                ?: error("missing never-reported device")
            assertEquals("unknown", never["presence"])
            assertTrue(never["lastSeenAt"] == null)
        }

        step("readings: list is newest first and filterable") {
            val result = get(
                "/my/devices/android/$externalId/readings?limit=100",
                user.token,
            )
            assertEquals(200, result.code)
            val page = readingPageAdapter.fromJson(result.body) ?: error("empty body")
            val items = page.items.orEmpty()
            assertEquals(3, items.size)
            assertFalse(page.hasMore ?: false)
            val times = items.mapNotNull { Rfc3339.parse(it.recordedAt.orEmpty()) }
            assertEquals(times.sortedDescending(), times)
            assertEquals(readings.map { it.id }.toSet(), items.mapNotNull { it.id }.toSet())

            val filtered = get(
                "/my/devices/android/$externalId/readings?metric=heart_rate&limit=100",
                user.token,
            )
            assertEquals(200, filtered.code)
            val filteredPage = readingPageAdapter.fromJson(filtered.body) ?: error("empty body")
            assertEquals(1, filteredPage.items.orEmpty().size)
            assertEquals("heart_rate", filteredPage.items.orEmpty().single().metric)
        }

        step("readings: 24h summary aggregates min max avg last") {
            val result = get(
                "/my/devices/android/$externalId/readings/summary?metric=temperature&hours=24",
                user.token,
            )
            assertEquals(200, result.code)
            val summary = readingSummaryAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals("temperature", summary.metric)
            assertEquals(2, summary.count)
            assertEquals(21.5, summary.min!!, 0.001)
            assertEquals(22.0, summary.max!!, 0.001)
            assertEquals(21.75, summary.avg!!, 0.001)
            assertEquals(22.0, summary.last!!, 0.001)
        }

        step("readings: NaN value is rejected with 400") {
            val invalidId = UUID.randomUUID().toString()
            val body = """
                {"clientId":"e2e-readings-client","devices":[],"readings":[
                  {"id":"$invalidId","platform":"android","externalId":"$externalId",
                   "metric":"temperature","value":NaN,"unit":"°C",
                   "recordedAt":"${Rfc3339.format(now)}"}
                ]}
            """.trimIndent()
            val result = push(body, user.token, "e2e-readings-invalid-${UUID.randomUUID()}")
            assertEquals(400, result.code)
        }

        step("readings: series returns ascending non-empty UTC buckets") {
            val from = Rfc3339.format(now - 2 * 3_600_000L)
            val to = Rfc3339.format(now + 3_600_000L)
            val result = get(
                "/my/devices/android/$externalId/readings/series" +
                    "?metric=temperature&from=$from&to=$to&bucket=1h",
                user.token,
            )
            assertEquals(200, result.code)
            val series = readingSeriesAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals("temperature", series.metric)
            assertEquals("1h", series.bucket)
            assertEquals("°C", series.unit)
            val buckets = series.buckets.orEmpty()
            assertTrue(buckets.isNotEmpty())
            val starts = buckets.mapNotNull { Rfc3339.parse(it.start.orEmpty()) }
            assertEquals(starts.sorted(), starts)
            // 服务端只返回有数据的桶：两条温度读数合计应落在桶内 count 之和。
            assertEquals(2L, buckets.sumOf { it.count ?: 0L })
            assertTrue(buckets.all { (it.count ?: 0L) > 0L })
        }

        step("readings: empty series range returns []") {
            val from = Rfc3339.format(now + 24 * 3_600_000L)
            val to = Rfc3339.format(now + 25 * 3_600_000L)
            val result = get(
                "/my/devices/android/$externalId/readings/series" +
                    "?metric=temperature&from=$from&to=$to&bucket=1h",
                user.token,
            )
            assertEquals(200, result.code)
            val series = readingSeriesAdapter.fromJson(result.body) ?: error("empty body")
            assertTrue(series.buckets.orEmpty().isEmpty())
        }

        step("readings: invalid bucket is rejected with 400") {
            val from = Rfc3339.format(now - 3_600_000L)
            val to = Rfc3339.format(now)
            val result = get(
                "/my/devices/android/$externalId/readings/series" +
                    "?metric=temperature&from=$from&to=$to&bucket=5m",
                user.token,
            )
            assertEquals(400, result.code)
        }

        step("readings: metrics are newest first with counts and units") {
            val result = get(
                "/my/devices/android/$externalId/readings/metrics",
                user.token,
            )
            assertEquals(200, result.code)
            val metrics = readingMetricsAdapter.fromJson(result.body) ?: error("empty body")
            val items = metrics.items.orEmpty()
            assertEquals(2, items.size)
            // temperature 最新一条在 now-30s，heart_rate 在 now-45s → 温度在前。
            assertEquals("temperature", items[0].metric)
            assertEquals("heart_rate", items[1].metric)
            assertEquals(2L, items[0].count)
            assertEquals(1L, items[1].count)
            assertEquals("°C", items[0].unit)
            assertEquals("bpm", items[1].unit)
            val lastAts = items.mapNotNull { Rfc3339.parse(it.lastAt.orEmpty()) }
            assertEquals(lastAts.sortedDescending(), lastAts)
        }

        // 与 App 内使用同一条生产代码路径：metrics 找 lastAt 最新指标，再 limit=1 取最近一条。
        step("readings: production latest path returns the newest cloud reading") {
            val tokenStore = object : TokenStore {
                override fun token(): String = user.token
                override fun saveToken(token: String) = Unit
                override fun clear() = Unit
            }
            val remote = RemoteSyncDataSource(
                api = syncApi,
                tokenStore = tokenStore,
                installId = "e2e-latest-readings",
            )
            val latest = runBlocking { remote.latest("android", externalId) }
            assertNotNull(latest)
            assertEquals("temperature", latest!!.metric)
            assertEquals(22.0, latest.value, 0.001)
            assertEquals("°C", latest.unit)
            val expectedAt = now - 30_000
            assertTrue(
                "recordedAt ${latest.recordedAt} should be near $expectedAt",
                latest.recordedAt in (expectedAt - 1_000)..(expectedAt + 1_000),
            )
        }

        println("[E2E] SUMMARY: readings contract cases passed")
    }

    /**
     * 账号契约（openapi 0.6.0）：配对码生成/重新生成使旧码失效、claim 换同一 userId 的新 token、
     * 404 统一文案、不透明游标分页导出、注销后全部 token 401。
     */
    @Test
    fun `account contract against local go backend`() {
        val user = captureStep("account: anonymous login") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            assertTokenTtl(body, "account anonymous login")
            UserSession(
                userId = body["userId"] as? String ?: error("missing userId"),
                token = body["token"] as? String ?: error("missing token"),
            )
        }

        val pairingCode = captureStep("account: create pairing code with server expiresAt") {
            val result = post("/auth/pairing-code", "{}", user.token)
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            val code = body["code"] as? String ?: error("missing code")
            val expiresAt = body["expiresAt"] as? String ?: error("missing expiresAt")
            // 服务端字符集剔除 0/O/1/I；客户端输入框用同一规则做过滤。
            assertTrue("unexpected code: $code", Regex("^[A-HJ-NP-Z2-9]{8}$").matches(code))
            val expiresMs = Rfc3339.parse(expiresAt) ?: error("bad expiresAt: $expiresAt")
            val ttl = expiresMs - System.currentTimeMillis()
            // 契约写死 10 分钟；允许 1 分钟的时钟/取整误差。
            assertTrue("ttl=$ttl", ttl in 9 * 60_000L..11 * 60_000L)
            code
        }

        val regeneratedCode = captureStep("account: regenerate returns a different code") {
            val result = post("/auth/pairing-code", "{}", user.token)
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            val code = body["code"] as? String ?: error("missing code")
            assertTrue("unexpected code: $code", Regex("^[A-HJ-NP-Z2-9]{8}$").matches(code))
            assertTrue(code != pairingCode)
            code
        }

        step("account: previous code is rejected with 404 NOT_FOUND") {
            val result = post("/auth/pairing-code/claim", claimBody(pairingCode))
            assertEquals(404, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("NOT_FOUND", error["code"])
            // 过期/已用/格式错误/不存在统一文案，客户端不需要区分原因。
            assertEquals("配对码无效或已过期", error["message"])
        }

        val claimed = captureStep("account: claim returns same userId with a new token") {
            val result = post("/auth/pairing-code/claim", claimBody(regeneratedCode))
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals(user.userId, body["userId"])
            val token = body["token"] as? String ?: error("missing token")
            assertTrue(token.isNotBlank())
            assertTrue("token should change", token != user.token)
            assertTokenTtl(body, "claim")
            UserSession(user.userId, token)
        }

        step("account: claimed + original token both stay valid") {
            assertEquals(200, get("/sync/changes?cursor=0", claimed.token).code)
            assertEquals(200, get("/sync/changes?cursor=0", user.token).code)
        }

        step("account: claimed code is single-use") {
            val result = post("/auth/pairing-code/claim", claimBody(regeneratedCode))
            assertEquals(404, result.code)
        }

        step("account: unknown code shares the same 404 message") {
            val result = post("/auth/pairing-code/claim", claimBody("ZZZZZZZZ"))
            assertEquals(404, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("NOT_FOUND", error["code"])
        }

        step("account: export and deletion require a token") {
            assertUnauthorized(post("/me/export"))
            assertUnauthorized(post("/me/deletion-request"))
        }

        val exportExternalId = "AA:BB:CC:00:00:03"
        val exportBase = System.currentTimeMillis() - 300_000
        val exportReadings = (0 until 5).map { index ->
            PushReadingDto(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = exportExternalId,
                metric = "temperature",
                value = 20.0 + index,
                unit = "°C",
                recordedAt = Rfc3339.format(exportBase + index * 30_000L),
            )
        }
        step("account: seed data for export") {
            val result = push(
                pushRequestAdapter.toJson(
                    SyncPushRequest(
                        clientId = "e2e-account-client",
                        devices = listOf(
                            PushDeviceDto(
                                id = UUID.randomUUID().toString(),
                                platform = "android",
                                externalId = exportExternalId,
                                mac = exportExternalId.lowercase(),
                                alias = Reported("导出设备"),
                                updatedAt = Rfc3339.format(exportBase),
                            ),
                        ),
                        readings = exportReadings,
                    ),
                ),
                claimed.token,
                "e2e-export-seed-${UUID.randomUUID()}",
            )
            assertEquals(200, result.code)
            val response = pushResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals(5, response.appliedReadings)
        }

        val exportTaskId = captureStep("account: export task is created asynchronously") {
            val result = post("/me/export", "{}", claimed.token)
            assertEquals(202, result.code)
            val task = parseMap(result.body)
            val id = task["id"] as? String ?: error("missing export task id")
            assertEquals("pending", task["status"])
            id
        }

        val exportView = captureStep("account: export task becomes ready with a one-time link") {
            var view = emptyMap<String, Any?>()
            var attempts = 0
            while (attempts < 40) {
                val result = get("/me/export/$exportTaskId", claimed.token)
                assertEquals(200, result.code)
                view = parseMap(result.body)
                if (view["status"] == "ready") break
                assertEquals("pending", view["status"])
                Thread.sleep(100)
                attempts += 1
            }
            assertEquals("ready", view["status"])
            assertNotNull(view["downloadUrl"])
            view
        }
        val downloadUrl = exportView["downloadUrl"] as? String ?: error("missing downloadUrl")

        step("account: export artifact is public, aggregated and single-use") {
            val first = executeAt(baseUrl, "GET", downloadUrl, null, null)
            assertEquals(200, first.code)
            val artifact = parseMap(first.body)
            assertEquals(claimed.userId, (artifact["user"] as? Map<*, *>)?.get("id"))
            val devices = artifact["devices"] as? List<*> ?: error("missing devices")
            assertTrue("export must contain the seeded device", devices.isNotEmpty())
            val aggregates = artifact["readings"] as? List<*> ?: error("missing readings")
            assertTrue("readings must be aggregated", aggregates.isNotEmpty())
            // openapi：下载链接一次性；同一链接第二次必须 410，客户端应重新查询任务。
            assertEquals(410, executeAt(baseUrl, "GET", downloadUrl, null, null).code)
        }

        step("account: expired download link is reissued by querying the task again") {
            val refreshed = parseMap(get("/me/export/$exportTaskId", claimed.token).body)
            val freshUrl = refreshed["downloadUrl"] as? String ?: error("missing fresh link")
            assertTrue(freshUrl != downloadUrl)
            assertEquals(200, executeAt(baseUrl, "GET", freshUrl, null, null).code)
        }

        // ---- 家庭链路（fixture 与 postgres 都执行） ----
        val householdDevice = run {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(6).chunked(2)
                .joinToString(":") { it.uppercase() }
            "AA:BB:CC:$suffix"
        }
        step("household: owner pushes one device") {
            val push = PushDeviceDto(
                id = UUID.randomUUID().toString(),
                platform = "android",
                externalId = householdDevice,
                mac = householdDevice.lowercase(),
                alias = Reported("家庭成员设备"),
                scanRecords = emptyList(),
                updatedAt = Rfc3339.format(System.currentTimeMillis() - 5_000),
            )
            val result = post(
                "/sync/push",
                pushRequestAdapter.toJson(
                    SyncPushRequest(clientId = "e2e-household-client", devices = listOf(push)),
                ),
                user.token,
            )
            assertEquals(200, result.code)
        }
        val householdId = captureStep("household: owner creates a household") {
            val result = post("/households", """{"name":"e2e-home"}""", user.token)
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing household id")
        }

        val roomId = captureStep("household: owner creates a room") {
            val result = post(
                "/households/$householdId/rooms",
                """{"name":"客厅","sortOrder":0}""",
                user.token,
            )
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing room id")
        }

        step("household: owner assigns device room via PATCH /my/devices") {
            val result = patch(
                "/my/devices/android/$householdDevice",
                """{"householdId":"$householdId","roomId":"$roomId"}""",
                user.token,
            )
            assertEquals(200, result.code)
            val device = parseMap(result.body)
            assertEquals(householdId, device["householdId"])
            assertEquals(roomId, device["roomId"])
        }

        val inviteCode = captureStep("household: owner issues an 8-char one-time invitation") {
            val result = post("/households/$householdId/invitations", "{}", user.token)
            assertEquals(201, result.code)
            val code = parseMap(result.body)["code"] as? String ?: error("missing invite code")
            assertTrue("invite code=$code", code.matches(Regex("[A-HJ-NP-Z2-9]{8}")))
            assertNotNull(parseMap(result.body)["expiresAt"])
            code
        }

        val member = captureStep("household: second account claims the invitation") {
            val session = newAnonymousSession("household member")
            val result = post("/households/invitations/claim", claimBody(inviteCode), session.token)
            assertEquals(200, result.code)
            assertEquals(householdId, parseMap(result.body)["id"])
            session
        }

        step("household: member sees the owner device with real room data") {
            val result = get("/my/devices", member.token)
            assertEquals(200, result.code)
            val device = parseMap(result.body)["items"]
                .let { it as? List<*> }
                .orEmpty()
                .map { it as? Map<*, *> }
                .firstOrNull { it?.get("externalId") == householdDevice }
                ?: error("member cannot see owner device")
            assertEquals(householdId, device["householdId"])
            assertEquals(roomId, device["roomId"])
        }

        step("household: owner sees owner and member roles") {
            val result = get("/households/$householdId/members", user.token)
            assertEquals(200, result.code)
            val members = (parseMap(result.body)["items"] as? List<*>).orEmpty()
                .map { it as? Map<*, *> }
            assertEquals(2, members.size)
            assertEquals("owner", members.firstOrNull { it?.get("userId") == user.userId }?.get("role"))
            assertEquals("member", members.firstOrNull { it?.get("userId") == member.userId }?.get("role"))
        }

        step("household: member renaming a room is rejected with 403") {
            val result = patch(
                "/households/$householdId/rooms/$roomId",
                """{"name":"阳台"}""",
                member.token,
            )
            assertEquals(403, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("FORBIDDEN", error["code"])
        }

        step("household: member creating a room is rejected with 403") {
            val result = post(
                "/households/$householdId/rooms",
                """{"name":"次卧","sortOrder":1}""",
                member.token,
            )
            assertEquals(403, result.code)
        }

        step("household: member cannot patch the owner device (404)") {
            val result = patch(
                "/my/devices/android/$householdDevice",
                """{"roomId":""}""",
                member.token,
            )
            assertEquals(404, result.code)
        }

        step("household: cross-household access returns 404") {
            val outsider = newAnonymousSession("household outsider")
            assertEquals(404, get("/households/$householdId/rooms", outsider.token).code)
            assertEquals(404, get("/households/$householdId", outsider.token).code)
        }

        // ---- v0.13.0：成员移除 / 退出 / 邀请码管理 / 整段重排 ----
        step("household: GET /households exposes server-side counts") {
            val result = get("/households", user.token)
            assertEquals(200, result.code)
            val row = (parseMap(result.body)["items"] as? List<*>).orEmpty()
                .map { it as? Map<*, *> }
                .firstOrNull { it?.get("id") == householdId }
                ?: error("household missing from list")
            assertEquals(2, (row["memberCount"] as? Number)?.toInt())
            assertEquals(1, (row["deviceCount"] as? Number)?.toInt())
            assertEquals(1, (row["roomCount"] as? Number)?.toInt())
        }

        val managedInvite = captureStep("household: invitation list keeps plaintext out") {
            val created = post("/households/$householdId/invitations", "{}", user.token)
            assertEquals(201, created.code)
            val code = parseMap(created.body)["code"] as? String ?: error("missing invite code")

            val listed = get("/households/$householdId/invitations", user.token)
            assertEquals(200, listed.code)
            // 明文码不得出现在列表响应里（否则等于把一次性码变成可反复读取）。
            assertFalse("invitation list leaked plaintext", listed.body.contains(code))
            val items = (parseMap(listed.body)["items"] as? List<*>).orEmpty()
                .map { it as? Map<*, *> }
            assertTrue("invitation list should not be empty", items.isNotEmpty())
            items.forEach { item ->
                assertNull(item?.get("code"))
                assertNotNull(item?.get("codeHashPrefix"))
                assertNotNull(item?.get("status"))
                assertNotNull(item?.get("expiresAt"))
            }
            // 上一段链路签发过一个仍在有效期内的码，这里全部撤销后再验证 claim 404。
            val invitationIds = items.mapNotNull { it?.get("id") as? String }
            assertTrue("missing invitation ids", invitationIds.isNotEmpty())
            code to invitationIds
        }

        step("household: revoked invitation can no longer be claimed (404)") {
            managedInvite.second.forEach { invitationId ->
                val revoked = delete(
                    "/households/$householdId/invitations/$invitationId",
                    user.token,
                )
                assertEquals(204, revoked.code)
            }
            val latecomer = newAnonymousSession("household latecomer")
            val claim = post(
                "/households/invitations/claim",
                claimBody(managedInvite.first),
                latecomer.token,
            )
            assertEquals(404, claim.code)
        }

        step("household: member cannot reorder rooms (403)") {
            val result = put(
                "/households/$householdId/rooms/order",
                """{"roomIds":["$roomId"]}""",
                member.token,
            )
            assertEquals(403, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("FORBIDDEN", error["code"])
        }

        step("household: reorder with a missing room returns 409") {
            val missing = put(
                "/households/$householdId/rooms/order",
                """{"roomIds":["00000000-0000-0000-0000-000000000000"]}""",
                user.token,
            )
            assertEquals(409, missing.code)
            val error = parseMap(missing.body)["error"] as Map<*, *>
            // v0.13.0 契约没有 ROOM_SET_MISMATCH，机读判定只用 409 + error.code。
            assertNotNull(error["code"])

            val ok = put(
                "/households/$householdId/rooms/order",
                """{"roomIds":["$roomId"]}""",
                user.token,
            )
            assertEquals(200, ok.code)
            assertEquals(roomId, ((parseMap(ok.body)["items"] as? List<*>).orEmpty().first() as? Map<*, *>)?.get("id"))
        }

        step("household: owner with members cannot leave (409)") {
            val result = post("/households/$householdId/leave", "{}", user.token)
            assertEquals(409, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertNotNull(error["code"])
        }

        step("household: owner removes member and member loses access (404)") {
            val removed = delete("/households/$householdId/members/${member.userId}", user.token)
            assertEquals(204, removed.code)
            assertEquals(404, get("/households/$householdId", member.token).code)
            assertEquals(404, get("/households/$householdId/members", member.token).code)
        }

        step("household: owner can leave after removing other members (204)") {
            val result = post("/households/$householdId/leave", "{}", user.token)
            assertEquals(204, result.code)
            assertEquals(404, get("/households/$householdId", user.token).code)
        }

        // ---- v0.14.0：成员 isSelf 与所有权转让 ----
        val transferHomeId = captureStep("transfer: owner creates a separate household") {
            val result = post("/households", """{"name":"e2e-transfer"}""", user.token)
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing household id")
        }

        val transferCode = captureStep("transfer: owner issues an invitation") {
            val created = post("/households/$transferHomeId/invitations", "{}", user.token)
            assertEquals(201, created.code)
            parseMap(created.body)["code"] as? String ?: error("missing invite code")
        }

        val successor = captureStep("transfer: successor claims the invitation") {
            val session = newAnonymousSession("ownership successor")
            val result = post(
                "/households/invitations/claim",
                claimBody(transferCode),
                session.token,
            )
            assertEquals(200, result.code)
            assertEquals(transferHomeId, parseMap(result.body)["id"])
            session
        }

        fun membersOf(token: String): List<Map<*, *>> {
            val result = get("/households/$transferHomeId/members", token)
            assertEquals(200, result.code)
            return (parseMap(result.body)["items"] as? List<*>).orEmpty()
                .mapNotNull { it as? Map<*, *> }
        }

        step("transfer: member list marks the caller via isSelf") {
            val ownerView = membersOf(user.token)
            assertEquals(2, ownerView.size)
            assertEquals(true, ownerView.firstOrNull { it["userId"] == user.userId }?.get("isSelf"))
            assertEquals(
                false,
                ownerView.firstOrNull { it["userId"] == successor.userId }?.get("isSelf"),
            )
            val successorView = membersOf(successor.token)
            assertEquals(
                true,
                successorView.firstOrNull { it["userId"] == successor.userId }?.get("isSelf"),
            )
            assertEquals(
                false,
                successorView.firstOrNull { it["userId"] == user.userId }?.get("isSelf"),
            )
        }

        step("transfer: member cannot transfer ownership (403)") {
            val result = post(
                "/households/$transferHomeId/transfer-ownership",
                """{"userId":"${successor.userId}"}""",
                successor.token,
            )
            assertEquals(403, result.code)
            assertEquals(
                "FORBIDDEN",
                (parseMap(result.body)["error"] as? Map<*, *>)?.get("code"),
            )
        }

        step("transfer: unknown target returns 404") {
            val result = post(
                "/households/$transferHomeId/transfer-ownership",
                """{"userId":"00000000-0000-0000-0000-000000000000"}""",
                user.token,
            )
            assertEquals(404, result.code)
        }

        step("transfer: owner hands ownership to the successor") {
            val result = post(
                "/households/$transferHomeId/transfer-ownership",
                """{"userId":"${successor.userId}"}""",
                user.token,
            )
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            // 响应直接反映转让后的身份：调用者降为 member，owner 指向接任者。
            assertEquals("member", body["role"])
            assertEquals(successor.userId, body["ownerUserId"])
        }

        step("transfer: old owner loses structural permissions (403)") {
            assertEquals(
                403,
                patch("/households/$transferHomeId", """{"name":"旧 owner 改名"}""", user.token).code,
            )
            assertEquals(
                403,
                post("/households/$transferHomeId/invitations", "{}", user.token).code,
            )
            val roles = membersOf(user.token)
            assertEquals("member", roles.firstOrNull { it["userId"] == user.userId }?.get("role"))
            assertEquals("owner", roles.firstOrNull { it["userId"] == successor.userId }?.get("role"))
        }

        step("transfer: new owner removes the previous owner, then leaves (204)") {
            assertEquals(
                204,
                delete("/households/$transferHomeId/members/${user.userId}", successor.token).code,
            )
            assertEquals(404, get("/households/$transferHomeId", user.token).code)
            assertEquals(
                204,
                post("/households/$transferHomeId/leave", "{}", successor.token).code,
            )
        }

        // ---- v0.15.0：家庭结构变更写入 change_log（household / room） ----
        // 翻页收集：cursor 是不透明值，必须一直跟随 nextCursor，直到 hasMore=false。
        fun structuralEvents(token: String, fromCursor: Long): List<SyncChangeDto> {
            val collected = mutableListOf<SyncChangeDto>()
            var cursor = fromCursor
            var guard = 0
            while (guard++ < 20) {
                val result = getWithQuery(
                    "/sync/changes",
                    token,
                    listOf("cursor" to cursor.toString(), "limit" to "100"),
                )
                assertEquals(200, result.code)
                val page = changesResponseAdapter.fromJson(result.body) ?: error("empty changes body")
                collected += page.changes.orEmpty()
                if (page.hasMore != true) return collected
                val next = page.nextCursor ?: error("hasMore without nextCursor")
                assertTrue("changes cursor did not advance: $cursor -> $next", next > cursor)
                cursor = next
            }
            error("changes pagination did not terminate")
        }

        fun latestCursor(token: String): Long =
            structuralEvents(token, 0L).maxOfOrNull { it.seq ?: 0L } ?: 0L

        fun householdUpserts(events: List<SyncChangeDto>, homeId: String): List<SyncDeviceDto> =
            events
                .filter { it.entity == "household" && it.entityId == homeId && it.op == "upsert" }
                .mapNotNull { it.payload }

        /** 每个房间最后一次 upsert 的 sortOrder；重复条目以最后一条为准（服务端按 seq 升序返回）。 */
        fun roomOrders(events: List<SyncChangeDto>, homeId: String): Map<String, Int> =
            events
                .filter {
                    it.entity == "room" &&
                        it.op == "upsert" &&
                        it.payload?.householdId == homeId &&
                        it.payload?.deleted != true
                }
                .mapNotNull { entry ->
                    val roomId = entry.entityId ?: return@mapNotNull null
                    val order = entry.payload?.sortOrder ?: return@mapNotNull null
                    roomId to order
                }
                .toMap()

        val structHomeName = "e2e-struct"
        val structCreateCursor = latestCursor(user.token)
        val structHomeId = captureStep("struct: owner creates a household") {
            val result = post("/households", """{"name":"$structHomeName"}""", user.token)
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing household id")
        }

        step("struct: household create emits a receiver-scoped upsert") {
            val upserts = householdUpserts(structuralEvents(user.token, structCreateCursor), structHomeId)
            assertEquals(1, upserts.size)
            val payload = upserts.single()
            assertEquals(structHomeName, payload.name)
            assertEquals(user.userId, payload.ownerUserId)
            assertEquals("owner", payload.role)
            assertEquals(1, payload.memberCount)
            assertFalse(payload.deleted ?: false)
        }

        val structInviteCode = captureStep("struct: owner issues an invitation") {
            val created = post("/households/$structHomeId/invitations", "{}", user.token)
            assertEquals(201, created.code)
            parseMap(created.body)["code"] as? String ?: error("missing invite code")
        }

        val structMember = captureStep("struct: second account claims the invitation") {
            val session = newAnonymousSession("struct member")
            val result = post(
                "/households/invitations/claim",
                claimBody(structInviteCode),
                session.token,
            )
            assertEquals(200, result.code)
            assertEquals(structHomeId, parseMap(result.body)["id"])
            session
        }

        step("struct: new member only receives the join-time household upsert") {
            val events = structuralEvents(structMember.token, 0L)
            val payload = householdUpserts(events, structHomeId).single()
            assertEquals("member", payload.role)
            assertEquals(2, payload.memberCount)
            assertEquals(user.userId, payload.ownerUserId)
            // 后端不回放历史房间结构，客户端只能靠 GET /households 补基线。
            assertEquals(0, events.count { it.entity == "room" })
        }

        val structRenameCursor = latestCursor(user.token)
        val structMemberCursor = latestCursor(structMember.token)
        val livingRoomId = captureStep("struct: owner creates the first room") {
            val result = post(
                "/households/$structHomeId/rooms",
                """{"name":"客厅","sortOrder":0}""",
                user.token,
            )
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing room id")
        }
        val bedroomId = captureStep("struct: owner creates the second room") {
            val result = post(
                "/households/$structHomeId/rooms",
                """{"name":"卧室","sortOrder":1}""",
                user.token,
            )
            assertEquals(201, result.code)
            parseMap(result.body)["id"] as? String ?: error("missing room id")
        }
        step("struct: owner renames the household") {
            val result = patch(
                "/households/$structHomeId",
                """{"name":"e2e-struct-改名"}""",
                user.token,
            )
            assertEquals(200, result.code)
        }
        step("struct: owner reorders rooms") {
            val result = put(
                "/households/$structHomeId/rooms/order",
                """{"roomIds":["$bedroomId","$livingRoomId"]}""",
                user.token,
            )
            assertEquals(200, result.code)
        }

        step("struct: rename reaches both accounts with the receiver-specific role") {
            val views = listOf(
                user.token to "owner",
                structMember.token to "member",
            )
            val cursors = mapOf(user.token to structRenameCursor, structMember.token to structMemberCursor)
            views.forEach { (token, role) ->
                val events = structuralEvents(token, cursors.getValue(token))
                val payload = householdUpserts(events, structHomeId).last()
                assertEquals("e2e-struct-改名", payload.name)
                assertEquals(2, payload.memberCount)
                assertEquals(role, payload.role)
                assertEquals(user.userId, payload.ownerUserId)
            }
        }

        step("struct: room upserts carry householdId and the reordered sortOrder") {
            val cursors = mapOf(user.token to structRenameCursor, structMember.token to structMemberCursor)
            cursors.forEach { (token, cursor) ->
                val orders = roomOrders(structuralEvents(token, cursor), structHomeId)
                assertEquals(0, orders[bedroomId] ?: -1)
                assertEquals(1, orders[livingRoomId] ?: -1)
            }
        }

        step("struct: unrelated account sees no structural rows for this household") {
            val outsider = newAnonymousSession("struct outsider")
            val events = structuralEvents(outsider.token, 0L)
            assertEquals(0, events.count { it.entity == "household" || it.entity == "room" })
        }

        val beforeTransferOwner = latestCursor(user.token)
        val beforeTransferMember = latestCursor(structMember.token)
        step("struct: ownership transfer publishes receiver-specific roles") {
            val result = post(
                "/households/$structHomeId/transfer-ownership",
                """{"userId":"${structMember.userId}"}""",
                user.token,
            )
            assertEquals(200, result.code)
            val previousOwnerView = householdUpserts(
                structuralEvents(user.token, beforeTransferOwner),
                structHomeId,
            ).last()
            assertEquals("member", previousOwnerView.role)
            assertEquals(structMember.userId, previousOwnerView.ownerUserId)
            val newOwnerView = householdUpserts(
                structuralEvents(structMember.token, beforeTransferMember),
                structHomeId,
            ).last()
            assertEquals("owner", newOwnerView.role)
            assertEquals(structMember.userId, newOwnerView.ownerUserId)
        }

        val removedCursor = latestCursor(user.token)
        step("struct: new owner removes the previous owner and renames afterwards") {
            assertEquals(
                204,
                delete("/households/$structHomeId/members/${user.userId}", structMember.token).code,
            )
            assertEquals(404, get("/households/$structHomeId", user.token).code)
            assertEquals(
                200,
                patch("/households/$structHomeId", """{"name":"e2e-struct-移除后再改名"}""", structMember.token).code,
            )
        }

        step("struct: removed member stops receiving new structural changes") {
            val events = structuralEvents(user.token, removedCursor)
            assertEquals(0, events.count { it.entity == "household" && it.entityId == structHomeId })
            assertEquals(
                0,
                events.count {
                    it.entity == "room" && it.entityId in setOf(livingRoomId, bedroomId)
                },
            )
        }

        step("struct: removed member still reads pre-departure structural history") {
            val events = structuralEvents(user.token, 0L)
            assertTrue(
                "removed member should keep historical household rows",
                events.any { it.entity == "household" && it.entityId == structHomeId && it.op == "upsert" },
            )
        }

        step("struct: remaining member sees the post-removal rename with memberCount 1") {
            val payload = householdUpserts(structuralEvents(structMember.token, 0L), structHomeId).last()
            assertEquals("e2e-struct-移除后再改名", payload.name)
            assertEquals(1, payload.memberCount)
            assertEquals("owner", payload.role)
        }

        val lateInviteCode = captureStep("struct: owner issues a late invitation") {
            val created = post("/households/$structHomeId/invitations", "{}", structMember.token)
            assertEquals(201, created.code)
            parseMap(created.body)["code"] as? String ?: error("missing invite code")
        }
        val lateMember = captureStep("struct: late account claims the invitation") {
            val session = newAnonymousSession("struct late member")
            val result = post(
                "/households/invitations/claim",
                claimBody(lateInviteCode),
                session.token,
            )
            assertEquals(200, result.code)
            session
        }
        step("struct: late member gets only the join-time upsert, not room history") {
            val events = structuralEvents(lateMember.token, 0L)
            val payload = householdUpserts(events, structHomeId).single()
            assertEquals("member", payload.role)
            assertEquals(2, payload.memberCount)
            assertEquals("e2e-struct-移除后再改名", payload.name)
            assertEquals(0, events.count { it.entity == "room" })
        }
        step("struct: remaining member removes the late member (204)") {
            assertEquals(
                204,
                delete("/households/$structHomeId/members/${lateMember.userId}", structMember.token).code,
            )
        }

        println("[E2E] SUMMARY: household contract cases passed")

        // ---- 反馈（openapi 0.12.0 / 后端 v0.16.0） ----
        fun feedbackBody(description: String) = feedbackCreateAdapter.toJson(
            FeedbackCreateRequest(
                kind = "bug",
                body = description,
                contact = "e2e@example.com",
                context = FeedbackContextDto(
                    appVersion = "0.19.0",
                    buildType = "debug",
                    osVersion = "Android 14 (API 34)",
                    deviceModel = "E2E Pixel",
                ),
            ),
        )

        val feedbackKey = "e2e-feedback-${UUID.randomUUID()}"
        val firstFeedbackBody = feedbackBody("e2e 反馈：同步偶发失败")
        val firstFeedbackId = captureStep("feedback: submit returns 201 with feedbackId") {
            val result = postFeedback("/feedback", firstFeedbackBody, user.token, feedbackKey)
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            val id = body["feedbackId"] as? String ?: error("missing feedbackId")
            assertTrue(id.isNotBlank())
            assertNotNull(Rfc3339.parse(body["createdAt"] as? String))
            id
        }

        step("feedback: same key + same body replays the same id") {
            val replay = postFeedback("/feedback", firstFeedbackBody, user.token, feedbackKey)
            assertEquals(200, replay.code)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            assertEquals(firstFeedbackId, parseMap(replay.body)["feedbackId"])
        }

        step("feedback: same key + different body -> 409 IDEMPOTENCY_CONFLICT") {
            val conflict = postFeedback(
                "/feedback",
                feedbackBody("e2e 反馈：内容已变更"),
                user.token,
                feedbackKey,
            )
            assertEquals(409, conflict.code)
            val error = parseMap(conflict.body)["error"] as Map<*, *>
            assertEquals("IDEMPOTENCY_CONFLICT", error["code"])
        }

        step("feedback: missing context is rejected with 400") {
            val result = postFeedback(
                "/feedback",
                """{"kind":"bug","body":"缺少 context"}""",
                user.token,
                "e2e-feedback-invalid-${UUID.randomUUID()}",
            )
            assertEquals(400, result.code)
        }

        val secondFeedbackId = captureStep("feedback: second submission for pagination") {
            val result = postFeedback(
                "/feedback",
                feedbackBody("e2e 反馈：第二条用于游标分页"),
                user.token,
                "e2e-feedback-${UUID.randomUUID()}",
            )
            assertEquals(201, result.code)
            parseMap(result.body)["feedbackId"] as? String ?: error("missing feedbackId")
        }

        step("feedback: my list returns own items without contact/diagnostics") {
            val result = getWithQuery(
                "/feedback/mine",
                token = user.token,
                query = listOf("limit" to "50"),
            )
            assertEquals(200, result.code)
            val page = parseMap(result.body)
            val items = page["items"] as? List<*> ?: error("missing items")
            val first = items.filterIsInstance<Map<String, Any?>>()
                .firstOrNull { it["feedbackId"] == firstFeedbackId }
                ?: error("submitted feedback missing from list")
            assertEquals("submitted", first["status"])
            assertEquals("bug", first["kind"])
            // 契约明确列表不返回隐私字段。
            assertFalse(first.containsKey("contact"))
            assertFalse(first.containsKey("diagnostics"))
        }

        step("feedback: cursor pagination reads the next page") {
            val firstPage = getWithQuery(
                "/feedback/mine",
                token = user.token,
                query = listOf("limit" to "1"),
            )
            assertEquals(200, firstPage.code)
            val page = parseMap(firstPage.body)
            val items = (page["items"] as List<*>).filterIsInstance<Map<String, Any?>>()
            assertEquals(1, items.size)
            assertEquals(true, page["hasMore"])
            val cursor = page["nextCursor"] as? String ?: error("missing nextCursor")
            assertTrue(cursor.isNotBlank())

            val nextPage = getWithQuery(
                "/feedback/mine",
                token = user.token,
                query = listOf("limit" to "1", "cursor" to cursor),
            )
            assertEquals(200, nextPage.code)
            val nextItems = (parseMap(nextPage.body)["items"] as List<*>)
                .filterIsInstance<Map<String, Any?>>()
            assertEquals(1, nextItems.size)
            assertTrue(nextItems.single()["feedbackId"] != items.single()["feedbackId"])
            assertTrue(
                setOf(firstFeedbackId, secondFeedbackId)
                    .contains(nextItems.single()["feedbackId"]),
            )
        }

        step("feedback: another account cannot read my feedback") {
            val result = getWithQuery(
                "/feedback/mine",
                token = lateMember.token,
                query = listOf("limit" to "20"),
            )
            assertEquals(200, result.code)
            val items = (parseMap(result.body)["items"] as List<*>)
                .filterIsInstance<Map<String, Any?>>()
            assertFalse(items.any { it["feedbackId"] == firstFeedbackId })
        }

        println("[E2E] SUMMARY: feedback contract cases passed")

        val deletionToken = captureStep("account: deletion token is issued to the owner") {
            val result = post("/me/deletion-request", "{}", claimed.token)
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            val token = body["token"] as? String ?: error("missing deletion token")
            assertNotNull(body["expiresAt"])
            token
        }

        step("account: token deletion returns 204 and revokes every token of the account") {
            // 无令牌 / 无效令牌都不能删除。
            assertEquals(400, executeAt(apiBase, "DELETE", "/me", null, null).code)
            assertEquals(
                403,
                executeAt(
                    apiBase,
                    "DELETE",
                    "/me",
                    null,
                    null,
                    extraHeaders = mapOf("X-Deletion-Token" to "not-a-deletion-token"),
                ).code,
            )

            val deleted = executeAt(
                apiBase,
                "DELETE",
                "/me",
                null,
                null,
                extraHeaders = mapOf("X-Deletion-Token" to deletionToken),
            )
            assertEquals(204, deleted.code)
            assertTrue(deleted.body.isEmpty())

            assertUnauthorized(get("/sync/changes?cursor=0", claimed.token))
            // claim 换出的 token 与原 token 属于同一账号：删除后一起失效。
            assertUnauthorized(get("/sync/changes?cursor=0", user.token))
            // 令牌一次性：重放同一令牌得到 404，账号已不存在。
            assertEquals(
                404,
                executeAt(
                    apiBase,
                    "DELETE",
                    "/me",
                    null,
                    null,
                    extraHeaders = mapOf("X-Deletion-Token" to deletionToken),
                ).code,
            )
        }

        println("[E2E] SUMMARY: account contract cases passed")
    }

    /**
     * 反馈限流（后端 v0.18.0 起固定 `Retry-After: 60`）。
     * 使用脚本另起的低限流实例（账号 3/min、burst 3），避免污染常规 e2e。
     * 断言错误码、Retry-After，以及客户端没有自动重试产生第 4 条记录。
     */
    @Test
    fun `feedback rate limit returns 429 with retry-after and no automatic retry`() {
        assumeTrue(
            "未配置 e2eFeedbackLimitBaseUrl，跳过低限流 fixture 用例",
            feedbackLimitBaseUrl.isNotBlank(),
        )
        val limitBase = "$feedbackLimitBaseUrl/api/v1"

        val login = executeAt(limitBase, "POST", "/auth/anonymous", null, null)
        assertEquals(201, login.code)
        val token = parseMap(login.body)["token"] as? String ?: error("missing token")

        fun feedbackBody(index: Int) = """
            {
              "kind": "bug",
              "body": "e2e 限流反馈 $index",
              "context": {
                "platform": "android",
                "appVersion": "0.20.0",
                "buildType": "debug",
                "osVersion": "Android 16",
                "deviceModel": "Pixel"
              }
            }
        """.trimIndent()

        step("feedback rate limit: first three submissions are accepted") {
            repeat(3) { index ->
                val result = executeAt(
                    limitBase,
                    "POST",
                    "/feedback",
                    feedbackBody(index),
                    token,
                    "e2e-limit-$index-${UUID.randomUUID()}",
                )
                assertEquals(201, result.code)
            }
        }

        step("feedback rate limit: fourth submission -> 429 FEEDBACK_RATE_LIMITED") {
            val result = executeAt(
                limitBase,
                "POST",
                "/feedback",
                feedbackBody(3),
                token,
                "e2e-limit-3-${UUID.randomUUID()}",
            )
            assertEquals(429, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("FEEDBACK_RATE_LIMITED", error["code"])
            assertEquals("60", result.headers["Retry-After"])
        }

        step("feedback rate limit: client did not auto-retry, list still has three") {
            fun listCount(): Int {
                val result = executeAt(
                    limitBase,
                    "GET",
                    "/feedback/mine",
                    null,
                    token,
                    query = listOf("limit" to "20"),
                )
                assertEquals(200, result.code)
                return (parseMap(result.body)["items"] as List<*>).size
            }

            assertEquals(3, listCount())
            // 等过 1 秒：若客户端有自动重试，限流桶会补回 1 个令牌并多出记录。
            Thread.sleep(1_100)
            assertEquals(3, listCount())
        }

        println("[E2E] SUMMARY: feedback rate limit cases passed")
    }

    /**
     * 设备类型目录（后端 v0.18.0 / openapi 0.14.0）：公开只读 + 条件请求。
     * 首请求拿 ETag / Last-Modified / Cache-Control，带 If-None-Match 命中 304。
     */
    @Test
    fun `device type catalog is public and supports conditional requests`() {
        val first = execute("GET", "/catalog/device-types", null, null)
        assertEquals(200, first.code)

        val body = parseMap(first.body)
        val version = (body["version"] as? Number)?.toLong() ?: error("missing version")
        assertTrue(version > 0)
        assertTrue((body["generatedAt"] as? String)?.isNotBlank() == true)
        val items = (body["items"] as List<*>).filterIsInstance<Map<String, Any?>>()
        assertTrue(items.isNotEmpty())
        items.forEach { item ->
            assertTrue((item["type"] as? String)?.isNotBlank() == true)
            assertTrue((item["displayName"] as? String)?.isNotBlank() == true)
            assertTrue((item["updatedAt"] as? String)?.isNotBlank() == true)
            (item["metrics"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?.forEach { metric ->
                    assertTrue((metric["key"] as? String)?.isNotBlank() == true)
                    assertTrue((metric["unit"] as? String)?.isNotBlank() == true)
                    assertTrue((metric["characteristicUuid"] as? String)?.isNotBlank() == true)
                }
        }

        val etag = first.headers["ETag"] ?: error("missing ETag")
        assertTrue(etag.contains("catalog"))
        assertTrue(first.headers["Last-Modified"]?.isNotBlank() == true)
        assertTrue(first.headers["Cache-Control"]?.isNotBlank() == true)

        step("catalog: If-None-Match hits 304 and reuses cache") {
            val notModified = executeAt(
                apiBase,
                "GET",
                "/catalog/device-types",
                body = null,
                token = null,
                extraHeaders = mapOf("If-None-Match" to etag),
            )
            assertEquals(304, notModified.code)
            assertTrue(notModified.body.isEmpty())
            // 304 必须带同一个 ETag，缓存校验器不会漂移。
            assertEquals(etag, notModified.headers["ETag"])
        }

        println("[E2E] SUMMARY: device type catalog cases passed")
    }

    /**
     * 真 Postgres 才执行的契约：会话到期、列表上限边界、序列/指标、导出、注销 401。
     * 410 全量重同步分支需要 SQL 改 server_ts + cleanup，由 scripts/e2e-postgres.sh 编排。
     */
    @Test
    fun `postgres store contract limits expiresAt and delete`() {
        assumeTrue("store=$store，跳过 postgres 专属契约", store == "postgres")
        println("[E2E] postgres-only contract start")

        val user = captureStep("pg: anonymous login with expiresAt") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            assertTokenTtl(body, "pg anonymous login")
            UserSession(
                userId = body["userId"] as? String ?: error("missing userId"),
                token = body["token"] as? String ?: error("missing token"),
            )
        }

        val externalId = "AA:BB:CC:00:00:44"
        val now = System.currentTimeMillis()
        val payload = SyncPushRequest(
            clientId = "e2e-pg-client",
            devices = listOf(
                PushDeviceDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = externalId,
                    mac = externalId.lowercase(),
                    deviceType = "nordic_uart",
                    updatedAt = Rfc3339.format(now),
                ),
                PushDeviceDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = "AA:BB:CC:00:00:45",
                    mac = "aa:bb:cc:00:00:45",
                    updatedAt = Rfc3339.format(now),
                ),
            ),
            readings = listOf(
                PushReadingDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = externalId,
                    metric = "temperature",
                    value = 21.5,
                    unit = "°C",
                    recordedAt = Rfc3339.format(now - 60_000),
                ),
                PushReadingDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = externalId,
                    metric = "humidity",
                    value = 45.0,
                    unit = "%",
                    recordedAt = Rfc3339.format(now - 30_000),
                ),
            ),
        )

        step("pg: push two devices and two readings") {
            val result = push(
                pushRequestAdapter.toJson(payload),
                user.token,
                "e2e-pg-seed-${UUID.randomUUID()}",
            )
            assertEquals(200, result.code)
            val response = pushResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertEquals(2, response.appliedReadings)
        }

        val cursorAfterPush = captureStep("pg: changes returns >0 cursor") {
            val result = get("/sync/changes?cursor=0&limit=500", user.token)
            assertEquals(200, result.code)
            val response = changesResponseAdapter.fromJson(result.body) ?: error("empty body")
            assertTrue(response.changes.orEmpty().isNotEmpty())
            val next = response.nextCursor ?: 0L
            assertTrue("nextCursor=$next", next > 0L)
            next.toString()
        }

        step("pg: changes limit 501 is rejected with 400") {
            assertEquals(400, get("/sync/changes?cursor=$cursorAfterPush&limit=501", user.token).code)
        }

        step("pg: readings limit boundary 500 ok / 501 rejected") {
            assertEquals(
                200,
                get("/my/devices/android/$externalId/readings?limit=500", user.token).code,
            )
            assertEquals(
                400,
                get("/my/devices/android/$externalId/readings?limit=501", user.token).code,
            )
        }

        step("pg: series and metrics work on real storage") {
            val from = Rfc3339.format(now - 3_600_000L)
            val to = Rfc3339.format(now + 60_000L)
            val series = get(
                "/my/devices/android/$externalId/readings/series" +
                    "?metric=temperature&from=$from&to=$to&bucket=1h",
                user.token,
            )
            assertEquals(200, series.code)
            assertTrue(readingSeriesAdapter.fromJson(series.body)!!.buckets.orEmpty().isNotEmpty())

            val metrics = get("/my/devices/android/$externalId/readings/metrics", user.token)
            assertEquals(200, metrics.code)
            assertEquals(2, readingMetricsAdapter.fromJson(metrics.body)!!.items.orEmpty().size)
        }

        step("pg: export task builds an artifact on real storage") {
            val created = post("/me/export", "{}", user.token)
            assertEquals(202, created.code)
            val taskId = parseMap(created.body)["id"] as? String ?: error("missing export id")
            var view = emptyMap<String, Any?>()
            var attempts = 0
            while (attempts < 60) {
                val result = get("/me/export/$taskId", user.token)
                assertEquals(200, result.code)
                view = parseMap(result.body)
                if (view["status"] == "ready") break
                assertEquals("pending", view["status"])
                Thread.sleep(200)
                attempts += 1
            }
            assertEquals("ready", view["status"])
            val url = view["downloadUrl"] as? String ?: error("missing downloadUrl")
            val download = executeAt(baseUrl, "GET", url, null, null)
            assertEquals(200, download.code)
            assertEquals(user.userId, (parseMap(download.body)["user"] as? Map<*, *>)?.get("id"))
        }

        step("pg: deletion token then 204 and token 401") {
            val issued = post("/me/deletion-request", "{}", user.token)
            assertEquals(201, issued.code)
            val deletionToken = parseMap(issued.body)["token"] as? String
                ?: error("missing deletion token")
            val deleted = executeAt(
                apiBase,
                "DELETE",
                "/me",
                null,
                null,
                extraHeaders = mapOf("X-Deletion-Token" to deletionToken),
            )
            assertEquals(204, deleted.code)
            assertUnauthorized(get("/sync/changes?cursor=0", user.token))
        }

        println("[E2E] SUMMARY: postgres-only contract passed")
    }

    /**
     * v0.22.0 场景链路跑在真 Postgres 上：首次上传返回 id/version（客户端据此写回 remoteId）、
     * 字段级 LWW 两端改不同字段都保留、软删后 GET/list 均不可见、deviceMissing 由服务端按设备目录实时计算。
     */
    @Test
    fun `postgres scenes contract upload lww soft delete and device missing`() {
        assumeTrue("store=$store，跳过 postgres 场景契约", store == "postgres")

        val owner = newAnonymousSession("pg scenes owner")
        val sceneTokenStore = TestTokenStore(owner.token)
        val scenes = RemoteSceneDataSource(ApiClient.createScenes(baseUrl), sceneTokenStore)
        val externalId = "AA:BB:CC:00:00:61"
        pushDevice(owner.token, externalId, idempotencyKey = "e2e-pg-scene-device-1")

        val created = captureStep("scene: first upload returns id/version for local writeback") {
            runBlocking {
                scenes.create(
                    SceneInputDto(
                        name = "Postgres 离线提醒",
                        enabled = true,
                        cooldownSeconds = 300,
                        trigger = SceneTriggerDto(
                            kind = "device_offline",
                            device = SceneDeviceRefDto(platform = "android", externalId = externalId),
                            offlineSeconds = 600,
                        ),
                        actions = listOf(
                            SceneActionDto(kind = "notify", title = "{device} 离线", body = "请检查"),
                        ),
                    ),
                )
            }
        }
        assertNotNull(created.remoteId)
        assertTrue("首次上传必须返回 version", created.version >= 1)
        assertEquals(
            "写回 remoteId 后可按 id 读回同一条",
            created.remoteId,
            runBlocking { scenes.list() }.single { it.name == "Postgres 离线提醒" }.remoteId,
        )

        val nameAt = SceneTimestamps.format(System.currentTimeMillis() + 10_000)
        val enabledAt = SceneTimestamps.format(System.currentTimeMillis() + 20_000)
        val renamed = runBlocking {
            scenes.update(created.remoteId, ScenePatchDto(name = SceneStringFieldDto("Postgres 离线提醒-改名", nameAt)))
        }
        assertEquals("Postgres 离线提醒-改名", renamed.name)
        assertTrue("改名不应改动其它字段", renamed.enabled)

        val disabled = runBlocking {
            scenes.update(created.remoteId, ScenePatchDto(enabled = SceneBoolFieldDto(false, enabledAt)))
        }
        assertEquals("另一端停用后，改名结果必须保留", "Postgres 离线提醒-改名", disabled.name)
        assertFalse("另一端的 enabled=false 必须生效", disabled.enabled)
        assertTrue("两端各自改一个字段，版本应递增两次", disabled.version > renamed.version)

        val stranger = newAnonymousSession("pg scenes stranger")
        step("scene: cross-user GET is 404") {
            assertEquals(404, get("/scenes/${created.remoteId}", stranger.token).code)
        }

        val missingRef = "AA:BB:CC:00:00:62"
        pushDevice(owner.token, missingRef, idempotencyKey = "e2e-pg-scene-device-2")
        val withMissing = captureStep("scene: deviceMissing follows the device directory, not cascade delete") {
            runBlocking {
                val created = scenes.create(
                    SceneInputDto(
                        name = "Postgres 设备缺失提醒",
                        enabled = true,
                        cooldownSeconds = 60,
                        trigger = SceneTriggerDto(
                            kind = "rssi_below",
                            device = SceneDeviceRefDto(platform = "android", externalId = missingRef),
                            rssiThreshold = -70,
                        ),
                        actions = listOf(SceneActionDto(kind = "log", message = "{device} 信号弱", level = "warn")),
                    ),
                )
                assertFalse(created.deviceMissing)
                pushDevice(owner.token, missingRef, idempotencyKey = "e2e-pg-scene-device-2-del", deleted = true)
                val afterDelete = scenes.list().single { it.remoteId == created.remoteId }
                assertTrue("设备删除后场景不级联删除，只标记 deviceMissing", afterDelete.deviceMissing)
                assertEquals("Postgres 设备缺失提醒", afterDelete.name)
                afterDelete
            }
        }
        assertEquals(withMissing.remoteId, runBlocking { scenes.list() }.single { it.name == "Postgres 设备缺失提醒" }.remoteId)

        step("scene: soft delete removes it from list and GET returns 404") {
            runBlocking { scenes.delete(created.remoteId) }
            val remaining = runBlocking { scenes.list() }
            assertTrue(remaining.none { it.remoteId == created.remoteId })
            assertEquals(404, get("/scenes/${created.remoteId}", owner.token).code)
            // 跨用户与已软删统一 404，不泄露存在性。
            assertEquals(404, get("/scenes/${created.remoteId}", stranger.token).code)
        }

        println("[E2E] SUMMARY: postgres scenes contract passed")
    }

    /**
     * v0.23.0 会话链路跑在真 Postgres 上：refresh 后旧 token 立刻 401、吊销单个会话后该 token 401、
     * 其它会话不受影响；跨用户场景 404 在场景用例中已覆盖。
     */
    @Test
    fun `postgres sessions contract refresh rotation and single revoke`() {
        assumeTrue("store=$store，跳过 postgres 会话契约", store == "postgres")

        val owner = newAnonymousSession("pg sessions owner")
        val issued = post("/auth/pairing-code", "{}", owner.token)
        assertEquals(201, issued.code)
        val code = parseMap(issued.body)["code"] as? String ?: error("missing pairing code")

        val second = captureStep("session: pairing code creates a second session of the same account") {
            val result = post("/auth/pairing-code/claim", claimBody(code))
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals(owner.userId, body["userId"])
            val token = body["token"] as? String ?: error("missing claimed token")
            assertTrue(token != owner.token)
            UserSession(userId = owner.userId, token = token)
        }

        step("session: list marks exactly one current session and lists both devices") {
            val result = get("/sessions", second.token)
            assertEquals(200, result.code)
            val sessions = (parseMap(result.body)["sessions"] as List<*>)
                .filterIsInstance<Map<String, Any?>>()
            assertEquals(2, sessions.size)
            assertEquals(1, sessions.count { it["current"] == true })
            assertTrue(sessions.all { !(it["id"] as String).isBlank() })
        }

        val otherSessionId = captureStep("session: revoking the other session only kills that token") {
            val result = get("/sessions", second.token)
            assertEquals(200, result.code)
            val sessions = (parseMap(result.body)["sessions"] as List<*>)
                .filterIsInstance<Map<String, Any?>>()
            val other = sessions.single { it["current"] != true }
            val id = other["id"] as? String ?: error("missing session id")
            assertEquals(204, delete("/sessions/$id", second.token).code)
            id
        }
        assertTrue(otherSessionId.isNotBlank())
        step("session: revoked token gets 401 while the current one still works") {
            assertUnauthorized(get("/my/devices", owner.token))
            assertEquals(200, get("/my/devices", second.token).code)
        }

        val refreshed = captureStep("session: refresh rotates the token and invalidates the old one") {
            val result = post("/auth/refresh", "{}", second.token)
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals(owner.userId, body["userId"])
            val token = body["token"] as? String ?: error("missing refreshed token")
            assertTrue(token != second.token)
            assertTokenTtl(body, "refresh")
            token
        }
        step("session: old token is 401 immediately after refresh, new token works") {
            assertUnauthorized(get("/my/devices", second.token))
            assertEquals(200, get("/my/devices", refreshed).code)
        }

        step("session: logout revokes the current token") {
            assertEquals(204, post("/auth/logout", "{}", refreshed).code)
            assertUnauthorized(get("/my/devices", refreshed))
        }

        println("[E2E] SUMMARY: postgres sessions contract passed")
    }

    private fun pushDevice(token: String, externalId: String, idempotencyKey: String, deleted: Boolean = false) {
        val now = System.currentTimeMillis()
        val payload = SyncPushRequest(
            clientId = "e2e-pg-device-$externalId",
            devices = listOf(
                PushDeviceDto(
                    id = UUID.randomUUID().toString(),
                    platform = "android",
                    externalId = externalId,
                    mac = externalId.lowercase(),
                    deviceType = "environmental_sensor",
                    updatedAt = Rfc3339.format(now),
                    deleted = deleted,
                ),
            ),
        )
        val result = push(pushRequestAdapter.toJson(payload), token, idempotencyKey)
        assertEquals("push device $externalId", 200, result.code)
    }

    /** e2e 只关心 token 取值；刷新/清空由 Authenticator 用例单独覆盖。 */
    private class TestTokenStore(private val value: String) : TokenStore {
        override fun token(): String = value

        override fun saveToken(token: String) = Unit

        override fun clear() = Unit
    }

    private fun claimBody(code: String): String = """{"code":"$code"}"""

    private fun newAnonymousSession(label: String): UserSession {
        val result = post("/auth/anonymous")
        assertEquals("$label anonymous login", 201, result.code)
        val body = parseMap(result.body)
        return UserSession(
            userId = body["userId"] as? String ?: error("$label missing userId"),
            token = body["token"] as? String ?: error("$label missing token"),
        )
    }


    /** token TTL 契约：180 天；允许 1 天误差，避免服务端取整/时钟漂移误报。 */
    private fun assertTokenTtl(body: Map<String, Any?>, label: String) {
        val raw = body["expiresAt"] as? String ?: error("$label missing expiresAt")
        val expiresAt = Rfc3339.parse(raw) ?: error("$label bad expiresAt: $raw")
        val ttl = expiresAt - System.currentTimeMillis()
        assertTrue("$label ttl=$ttl", ttl in 179L * 86_400_000L..181L * 86_400_000L)
    }

    private data class UserSession(val userId: String, val token: String)

    /**
     * Push 设备契约（fixture 与 Postgres 都跑）：注册、同 token 幂等、
     * inactive 重置、400 PUSH_DEVICE_INVALID、跨用户 404 与幂等注销。
     */
    @org.junit.Test
    fun `push devices contract registration idempotency and deactivation`() {
        val user = captureStep("push: anonymous login") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            UserSession(body["userId"] as String, body["token"] as String)
        }
        val other = captureStep("push: other user anonymous login") {
            val result = post("/auth/anonymous")
            assertEquals(201, result.code)
            val body = parseMap(result.body)
            UserSession(body["userId"] as String, body["token"] as String)
        }

        step("push: unauthenticated register is 401") {
            assertUnauthorized(post("/push/devices", pushBody("fcm-token")))
        }
        step("push: missing token is 400 PUSH_DEVICE_INVALID") {
            val result = post("/push/devices", """{"platform":"android"}""", user.token)
            assertEquals(400, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("PUSH_DEVICE_INVALID", error["code"])
        }
        step("push: missing platform is 400 PUSH_DEVICE_INVALID") {
            val result = post("/push/devices", """{"token":"fcm-token"}""", user.token)
            assertEquals(400, result.code)
            val error = parseMap(result.body)["error"] as Map<*, *>
            assertEquals("PUSH_DEVICE_INVALID", error["code"])
        }

        val token = "fcm-e2e-${java.util.UUID.randomUUID()}"
        val firstDeviceId = captureStep("push: register device") {
            val result = post("/push/devices", pushBody(token), user.token)
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals("active", body["status"])
            assertEquals("android", body["platform"])
            body["id"] as String
        }

        step("push: same token upserts the same device id") {
            val result = post(
                "/push/devices",
                """{"platform":"android","token":"$token","appVersion":"0.29.0","deviceModel":"Pixel 8"}""",
                user.token,
            )
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals(firstDeviceId, body["id"])
            assertEquals("Pixel 8", body["deviceModel"])
            assertEquals("active", body["status"])
        }

        step("push: another user cannot deactivate this device") {
            assertEquals(404, delete("/push/devices/$firstDeviceId", other.token).code)
        }

        step("push: deactivate is 204 and idempotent") {
            assertEquals(204, delete("/push/devices/$firstDeviceId", user.token).code)
            assertEquals(204, delete("/push/devices/$firstDeviceId", user.token).code)
        }

        step("push: re-registering an inactive token resets it to active") {
            val result = post("/push/devices", pushBody(token), user.token)
            assertEquals(200, result.code)
            val body = parseMap(result.body)
            assertEquals(firstDeviceId, body["id"])
            assertEquals("active", body["status"])
        }
    }

    private fun pushBody(token: String): String =
        """{"platform":"android","token":"$token","appVersion":"0.29.0","deviceModel":"E2E Device"}"""

    private data class HttpResult(
        val code: Int,
        val body: String,
        val headers: Headers,
    )

    private fun step(name: String, block: () -> Unit) {
        println("[E2E] -> $name")
        block()
        println("[E2E] PASS $name")
    }

    private fun <T> captureStep(name: String, block: () -> T): T {
        println("[E2E] -> $name")
        val value = block()
        println("[E2E] PASS $name")
        return value
    }

    private fun get(path: String, token: String? = null): HttpResult =
        execute("GET", path, body = null, token = token)

    /** 带查询参数的 GET：cursor 是不透明串，必须交给 HttpUrl 做 URL 编码，不能字符串拼接。 */
    private fun getWithQuery(
        path: String,
        token: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): HttpResult = execute("GET", path, body = null, token = token, query = query)

    private fun post(
        path: String,
        body: String = "{}",
        token: String? = null,
    ): HttpResult = execute("POST", path, body = body, token = token)

    private fun delete(path: String, token: String): HttpResult =
        execute("DELETE", path, body = null, token = token)

    private fun patch(path: String, body: String, token: String): HttpResult =
        execute("PATCH", path, body = body, token = token)

    private fun put(path: String, body: String, token: String): HttpResult =
        execute("PUT", path, body = body, token = token)

    private fun push(body: String, token: String, idempotencyKey: String): HttpResult =
        execute(
            "POST",
            "/sync/push",
            body = body,
            token = token,
            idempotencyKey = idempotencyKey,
        )

    private fun postFeedback(
        path: String,
        body: String,
        token: String,
        idempotencyKey: String,
    ): HttpResult = execute(
        "POST",
        path,
        body = body,
        token = token,
        idempotencyKey = idempotencyKey,
    )

    private fun execute(
        method: String,
        path: String,
        body: String?,
        token: String?,
        idempotencyKey: String? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): HttpResult = executeAt(apiBase, method, path, body, token, idempotencyKey, query)

    private fun executeAt(
        base: String,
        method: String,
        path: String,
        body: String?,
        token: String?,
        idempotencyKey: String? = null,
        query: List<Pair<String, String>> = emptyList(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val url = (base + path).toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val builder = Request.Builder().url(url)
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "PATCH" -> builder.patch(
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )

            "PUT" -> builder.put(
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )

            else -> builder.post(
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        }
        token?.let { builder.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        client.newCall(builder.build()).execute().use { response ->
            return HttpResult(
                code = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers,
            )
        }
    }

    private fun parseMap(json: String): Map<String, Any?> =
        mapAdapter.fromJson(json) ?: error("empty JSON body")

    private fun assertUnauthorized(result: HttpResult) {
        assertEquals(401, result.code)
        val error = parseMap(result.body)["error"] as Map<*, *>
        assertEquals("UNAUTHORIZED", error["code"])
    }
}
