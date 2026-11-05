package com.platojobs.pkotlinapp.e2e

import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.PushReadingDto
import com.platojobs.pkotlinapp.data.remote.ReadingMetricsResponse
import com.platojobs.pkotlinapp.data.remote.ReadingPageResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSeriesResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSummaryResponse
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncPushResponse
import com.platojobs.pkotlinapp.data.remote.SyncScanRecordDto
import com.platojobs.pkotlinapp.data.remote.SyncApi
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
    private val apiBase = "$baseUrl/api/v1"

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

    @Test
    fun `contract against local go backend`() {
        println("[E2E] baseUrl=$baseUrl")

        step("health 200 + fixture store") {
            val result = get("/health")
            assertEquals(200, result.code)
            val health = parseMap(result.body)
            assertEquals("ok", health["status"])
            assertEquals("fixture", health["store"])
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
            UserSession(userId, token)
        }

        step("missing token -> 401 UNAUTHORIZED") {
            assertUnauthorized(get("/sync/changes?cursor=0"))
            assertUnauthorized(post("/sync/push", "{}"))
        }

        val now = System.currentTimeMillis()
        val baseUpdatedAt = now - 10_000
        val createdAt = now - 10_000
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

            // 冲突未落库：服务端仍是新名称，且没有产生新的 change。
            val myDevice = parseMap(
                get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body,
            )
            assertEquals("共享型号-Android", myDevice["alias"])
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
        }

        step("clearing alias sends explicit empty string and keeps advertisedName") {
            val cleared = androidPush.copy(
                alias = Reported(""),
                advertisedName = null,
                group = null,
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
        }

        step("scan after alias clear does not revive the old alias") {
            val scan = androidPush.copy(
                alias = null,
                advertisedName = Reported("PJ-Sensor-4"),
                group = null,
                updatedAt = Rfc3339.format(now + 40_000),
            )
            val json = pushRequestAdapter.toJson(
                SyncPushRequest("e2e-android-client", listOf(scan)),
            )
            assertFalse(json.contains("\"alias\""))

            assertEquals(
                200,
                push(json, user.token, "e2e-alias-no-revive-${UUID.randomUUID()}").code,
            )
            val body = parseMap(get("/my/devices/android/AA:BB:CC:00:00:01", user.token).body)
            assertEquals("", body["alias"])
            assertEquals("PJ-Sensor-4", body["advertisedName"])
            assertEquals("", body["group"])
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

        step("account: export requires a token") {
            assertUnauthorized(get("/me/export"))
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

        step("account: export loops over opaque cursor until hasMore=false") {
            var cursor: String? = null
            var pages = 0
            val collected = mutableListOf<Map<*, *>>()
            var devices = emptyList<Any?>()
            while (true) {
                val result = getWithQuery(
                    "/me/export",
                    token = claimed.token,
                    query = buildList {
                        add("limit" to "2")
                        cursor?.let { add("cursor" to it) }
                    },
                )
                assertEquals(200, result.code)
                val page = parseMap(result.body)
                val userInfo = page["user"] as? Map<*, *> ?: error("missing user")
                assertEquals(claimed.userId, userInfo["id"])
                pages += 1
                assertTrue("too many pages", pages <= 10)
                // 客户端不解析 cursor：只保证它非空、逐页变化且可原样回传。
                val next = page["nextCursor"] as? String
                val hasMore = page["hasMore"] as? Boolean ?: false
                @Suppress("UNCHECKED_CAST")
                (page["readings"] as? List<Map<String, Any?>>)
                    ?.let { collected += it.map { row -> row as Map<*, *> } }
                @Suppress("UNCHECKED_CAST")
                (page["devices"] as? List<Map<String, Any?>>)
                    ?.let { devices = it }
                if (!hasMore) {
                    assertEquals("", next)
                    break
                }
                assertTrue("nextCursor must not be blank", !next.isNullOrBlank())
                assertTrue("cursor must advance", next != cursor)
                cursor = next
            }
            assertEquals(5, collected.size)
            assertEquals(5, collected.map { it["id"] }.toSet().size)
            // 服务端按 recordedAt 升序分页，跨页合并后仍必须单调递增。
            val times = collected.map { Rfc3339.parse(it["recordedAt"] as String) ?: 0L }
            assertEquals(times.sorted(), times)
            assertEquals(exportReadings.map { it.id }.toSet(), collected.map { it["id"] }.toSet())
            assertTrue(pages >= 2)
            assertTrue(devices.isNotEmpty())
        }

        step("account: export via production Retrofit mapping") {
            val page = runBlocking {
                syncApi.exportMyData("Bearer ${claimed.token}", cursor = null, limit = 2)
            }
            assertEquals(claimed.userId, page.user?.get("id"))
            assertEquals(2, page.readings.orEmpty().size)
            assertEquals(true, page.hasMore)
            assertNotNull(page.nextCursor)
        }

        step("account: delete returns 204 and revokes every token of the account") {
            val deleted = delete("/me", claimed.token)
            assertEquals(204, deleted.code)
            assertTrue(deleted.body.isEmpty())

            assertUnauthorized(get("/sync/changes?cursor=0", claimed.token))
            // claim 换出的 token 与原 token 属于同一账号：注销后一起失效。
            assertUnauthorized(get("/sync/changes?cursor=0", user.token))
            // 幂等语义：重复注销返回 401，而不是 404。
            assertEquals(401, delete("/me", user.token).code)
        }

        println("[E2E] SUMMARY: account contract cases passed")
    }

    private fun claimBody(code: String): String = """{"code":"$code"}"""

    private data class UserSession(val userId: String, val token: String)

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

    private fun push(body: String, token: String, idempotencyKey: String): HttpResult =
        execute(
            "POST",
            "/sync/push",
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
    ): HttpResult {
        val url = (apiBase + path).toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
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
