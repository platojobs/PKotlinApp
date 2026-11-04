package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncErrorKindTest {

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `unknown host and connect failures are network errors`() {
        assertEquals(SyncErrorKind.NETWORK, classifySyncError(UnknownHostException("no dns")))
        assertEquals(SyncErrorKind.NETWORK, classifySyncError(ConnectException("refused")))
    }

    @Test
    fun `socket timeout is timeout`() {
        assertEquals(SyncErrorKind.TIMEOUT, classifySyncError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `auth expired exception maps to auth`() {
        assertEquals(SyncErrorKind.AUTH, classifySyncError(AuthExpiredException()))
    }

    @Test
    fun `http 401 and 403 map to auth`() {
        assertEquals(SyncErrorKind.AUTH, classifySyncError(httpError(401)))
        assertEquals(SyncErrorKind.AUTH, classifySyncError(httpError(403)))
    }

    @Test
    fun `http 409 maps to conflict`() {
        assertEquals(SyncErrorKind.CONFLICT, classifySyncError(httpError(409)))
    }

    @Test
    fun `http 500 maps to server error`() {
        assertEquals(SyncErrorKind.SERVER, classifySyncError(httpError(500)))
    }

    @Test
    fun `unexpected exception maps to unknown`() {
        assertEquals(SyncErrorKind.UNKNOWN, classifySyncError(RuntimeException("boom")))
    }
}
