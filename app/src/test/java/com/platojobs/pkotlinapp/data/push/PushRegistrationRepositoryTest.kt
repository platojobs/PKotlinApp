package com.platojobs.pkotlinapp.data.push

import com.platojobs.pkotlinapp.domain.push.PushDeviceRecord
import com.platojobs.pkotlinapp.domain.push.PushDeviceStatus
import com.platojobs.pkotlinapp.domain.push.PushRegisteredDevice
import com.platojobs.pkotlinapp.domain.push.PushRegistrationStatus
import com.platojobs.pkotlinapp.domain.push.PushRemote
import com.platojobs.pkotlinapp.domain.push.PushRemoteException
import com.platojobs.pkotlinapp.domain.push.PushTokenProvider
import com.platojobs.pkotlinapp.domain.push.PushTokenResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推送注册生命周期：注册、重注册、注销、退避、400 终态与权限解耦。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationRepositoryTest {

    private val store = FakeStore()
    private val remote = FakeRemote()
    private val provider = FakeProvider(PushTokenResult.Available("fcm-token-1"))
    private var now = 1_000L

    @Test
    fun `not configured never invents a token or calls the server`() = runTest {
        provider.result = PushTokenResult.NotConfigured
        val repository = repository()

        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(0, remote.registerCalls.size)
        val row = store.findByToken("")
        assertNotNull(row)
        assertEquals(PushDeviceStatus.UNCONFIGURED, row!!.status)
        assertTrue(row.lastError!!.contains("未配置 FCM"))
    }

    @Test
    fun `available token registers and repeated session ready is idempotent`() = runTest {
        val repository = repository()

        repository.onSessionReady()
        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.REGISTERED, repository.state.value.status)
        assertEquals(listOf("fcm-token-1"), remote.registerCalls)
        val row = store.findByToken("fcm-token-1")!!
        assertEquals(PushDeviceStatus.REGISTERED, row.status)
        assertEquals("remote-1", row.remoteDeviceId)
    }

    @Test
    fun `token rotation deactivates old device before registering the new token`() = runTest {
        store.save(
            PushDeviceRecord(
                token = "old-token",
                remoteDeviceId = "remote-old",
                status = PushDeviceStatus.REGISTERED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val repository = repository()

        repository.onTokenChanged()

        assertEquals(listOf("remote-old"), remote.unregisterCalls)
        assertEquals(PushDeviceStatus.INACTIVE, store.findByToken("old-token")!!.status)
        assertEquals(PushDeviceStatus.REGISTERED, store.findByToken("fcm-token-1")!!.status)
        assertEquals(PushRegistrationStatus.REGISTERED, repository.state.value.status)
    }

    @Test
    fun `network failure keeps pending and retries with exponential backoff`() = runTest {
        remote.registerFailures = mutableListOf(
            PushRemoteException.Retryable("offline"),
            PushRemoteException.Retryable("offline"),
            null,
        )
        val repository = repository()

        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.PENDING, repository.state.value.status)
        assertEquals(1, repository.state.value.retryCount)
        assertEquals(now + 30_000L, repository.state.value.nextRetryAt)
        assertTrue(store.findByToken("fcm-token-1")!!.status == PushDeviceStatus.PENDING)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, repository.state.value.retryCount)
        assertEquals(now + 60_000L, repository.state.value.nextRetryAt)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(PushRegistrationStatus.REGISTERED, repository.state.value.status)
        assertEquals(3, remote.registerCalls.size)
    }

    @Test
    fun `400 rejection is terminal and never retried`() = runTest {
        remote.registerFailures = mutableListOf(PushRemoteException.Rejected("PUSH_DEVICE_INVALID"))
        val repository = repository()

        repository.onSessionReady()
        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.REJECTED, repository.state.value.status)
        assertTrue(repository.state.value.lastError!!.contains("PUSH_DEVICE_INVALID"))
        assertEquals(1, remote.registerCalls.size)
        val row = store.findByToken("fcm-token-1")!!
        assertEquals(PushDeviceStatus.REJECTED, row.status)
        assertEquals(0, row.retryCount)
        assertNull(row.nextRetryAt)
    }

    @Test
    fun `notification permission denial does not block registration`() = runTest {
        val repository = repository(notificationsGranted = { false })

        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.REGISTERED, repository.state.value.status)
        assertFalse(repository.state.value.notificationsGranted)
        assertEquals(1, remote.registerCalls.size)
    }

    @Test
    fun `logout deletes remote device before clearing local mapping`() = runTest {
        val repository = repository()
        repository.onSessionReady()

        repository.unregisterAll()

        assertEquals(listOf("remote-1"), remote.unregisterCalls)
        assertEquals(PushDeviceStatus.INACTIVE, store.findByToken("fcm-token-1")!!.status)
        assertNull(store.findByToken("fcm-token-1")!!.remoteDeviceId)
        assertEquals(PushRegistrationStatus.INACTIVE, repository.state.value.status)
    }

    @Test
    fun `unregister failure still clears local state`() = runTest {
        remote.unregisterFailure = PushRemoteException.Retryable("offline")
        val repository = repository()
        repository.onSessionReady()

        repository.unregisterAll()

        assertEquals(PushDeviceStatus.INACTIVE, store.findByToken("fcm-token-1")!!.status)
        assertEquals(PushRegistrationStatus.INACTIVE, repository.state.value.status)
    }

    @Test
    fun `blank token is never sent to the server`() = runTest {
        provider.result = PushTokenResult.Available("   ")
        val repository = repository()

        repository.onSessionReady()

        assertEquals(0, remote.registerCalls.size)
        assertEquals(PushRegistrationStatus.PENDING, repository.state.value.status)
        assertTrue(repository.state.value.lastError!!.contains("令牌为空"))
    }

    @Test
    fun `provider failure keeps pending with scheduled retry`() = runTest {
        provider.result = PushTokenResult.Failed("token 获取失败")
        val repository = repository()

        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.PENDING, repository.state.value.status)
        assertEquals("token 获取失败", repository.state.value.lastError)
        assertEquals(0, remote.registerCalls.size)
    }

    @Test
    fun `register is skipped until a session exists`() = runTest {
        val repository = PushRegistrationRepository(
            store = store,
            tokenProvider = provider,
            remote = remote,
            appVersion = "0.29.0",
            deviceModel = "Pixel Test",
            scope = backgroundScope,
            sessionAvailable = { false },
            clock = { now },
        )

        repository.onSessionReady()

        assertEquals(PushRegistrationStatus.IDLE, repository.state.value.status)
        assertEquals(0, remote.registerCalls.size)
        assertNull(store.findByToken(""))
    }

    @Test
    fun `refresh restores registered state from local store`() = runTest {
        store.save(
            PushDeviceRecord(
                token = "fcm-token-1",
                remoteDeviceId = "remote-9",
                status = PushDeviceStatus.REGISTERED,
                createdAt = 10L,
                updatedAt = 20L,
            ),
        )
        val repository = repository()

        repository.refresh()

        assertEquals(PushRegistrationStatus.REGISTERED, repository.state.value.status)
        assertEquals("remote-9", repository.state.value.remoteDeviceId)
    }

    private fun TestScope.repository(notificationsGranted: () -> Boolean = { true }) =
        PushRegistrationRepository(
            store = store,
            tokenProvider = provider,
            remote = remote,
            appVersion = "0.29.0",
            deviceModel = "Pixel Test",
            scope = backgroundScope,
            sessionAvailable = { true },
            notificationsGranted = notificationsGranted,
            clock = { now },
        )

    private class FakeProvider(var result: PushTokenResult) : PushTokenProvider {
        override suspend fun fetchToken(): PushTokenResult = result
    }

    private class FakeRemote : PushRemote {
        val registerCalls = mutableListOf<String>()
        val unregisterCalls = mutableListOf<String>()

        /** 依次消费；null 表示该次成功。 */
        var registerFailures: MutableList<PushRemoteException?> = mutableListOf()
        var unregisterFailure: PushRemoteException? = null

        override suspend fun register(
            token: String,
            appVersion: String,
            deviceModel: String,
        ): PushRegisteredDevice {
            registerCalls += token
            val failure = if (registerFailures.isEmpty()) null else registerFailures.removeAt(0)
            if (failure != null) throw failure
            return PushRegisteredDevice("remote-${registerCalls.size}", "active")
        }

        override suspend fun unregister(remoteDeviceId: String) {
            unregisterCalls += remoteDeviceId
            unregisterFailure?.let { throw it }
        }
    }

    private class FakeStore : PushDeviceStore {
        private val rows = linkedMapOf<String, PushDeviceRecord>()

        override suspend fun findByToken(token: String): PushDeviceRecord? = rows[token]

        override suspend fun registered(): List<PushDeviceRecord> =
            rows.values.filter { it.status == PushDeviceStatus.REGISTERED }

        override suspend fun pendingOrRejected(): List<PushDeviceRecord> =
            rows.values.filter {
                it.status == PushDeviceStatus.PENDING || it.status == PushDeviceStatus.REJECTED
            }

        override suspend fun unconfigured(): PushDeviceRecord? =
            rows.values.firstOrNull { it.status == PushDeviceStatus.UNCONFIGURED }

        override suspend fun save(record: PushDeviceRecord) {
            rows[record.token] = record
        }
    }
}
