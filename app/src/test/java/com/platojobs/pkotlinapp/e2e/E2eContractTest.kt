package com.platojobs.pkotlinapp.e2e

import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncPushResponse
import com.platojobs.pkotlinapp.data.remote.SyncScanRecordDto
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.sync.toDomain
import com.platojobs.pkotlinapp.data.sync.toPushDto
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

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
            alias = "共享型号-Android",
            group = "联调",
            scanRecords = listOf(
                SyncScanRecordDto(rssi = -55, at = Rfc3339.format(createdAt)),
            ),
            updatedAt = Rfc3339.format(baseUpdatedAt),
        )
        val iosPush = PushDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = "ios",
            externalId = "E621E1F8-C36C-495A-93FC-0C247A3E6E5F",
            alias = "共享型号-iOS",
            group = "联调",
            scanRecords = emptyList(),
            updatedAt = Rfc3339.format(baseUpdatedAt),
        )
        val pushRequest = SyncPushRequest(
            clientId = "e2e-android-client",
            devices = listOf(androidPush, iosPush),
        )
        val pushJson = pushRequestAdapter.toJson(pushRequest)
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
            val oldAndroid = androidPush.copy(
                alias = "旧名称-冲突",
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
            assertEquals("旧名称-冲突", conflict.client?.alias)

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

            val type = (items.first() as? Map<*, *>)?.get("type") as? String
                ?: error("missing type")
            val catalog = parseMap(get("/device-types/$type/catalog").body)

            val deviceType = catalog["deviceType"] as? Map<*, *> ?: error("missing deviceType")
            assertEquals(type, deviceType["type"])
            assertTrue(catalog["firmwares"] is List<*>)
            assertTrue(catalog["accessories"] is List<*>)
        }

        println("[E2E] SUMMARY: all contract cases passed")
    }

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

    private fun post(
        path: String,
        body: String = "{}",
        token: String? = null,
    ): HttpResult = execute("POST", path, body = body, token = token)

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
    ): HttpResult {
        val builder = Request.Builder().url(apiBase + path)
        if (method == "GET") {
            builder.get()
        } else {
            builder.post(
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
