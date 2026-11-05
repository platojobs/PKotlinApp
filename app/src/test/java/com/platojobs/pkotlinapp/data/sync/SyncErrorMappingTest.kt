package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.ApiErrorCodes
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.CursorExpiredException
import com.platojobs.pkotlinapp.domain.sync.ServerBusyException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SyncErrorMappingTest {

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `http status and exception map to distinct kinds`() {
        assertEquals(SyncErrorKind.AUTH, classifySyncError(AuthExpiredException()))
        assertEquals(SyncErrorKind.RESYNC, classifySyncError(CursorExpiredException()))
        assertEquals(SyncErrorKind.BUSY, classifySyncError(ServerBusyException()))
        assertEquals(SyncErrorKind.AUTH, classifySyncError(httpError(401)))
        assertEquals(SyncErrorKind.RESYNC, classifySyncError(httpError(410)))
        assertEquals(SyncErrorKind.BUSY, classifySyncError(httpError(503)))
        assertEquals(SyncErrorKind.CONFLICT, classifySyncError(httpError(409)))
        assertEquals(SyncErrorKind.SERVER, classifySyncError(httpError(500)))
    }

    @Test
    fun `error codes include resync timeout and idempotency conflict`() {
        assertEquals(SyncErrorKind.AUTH, classifyApiErrorCode(ApiErrorCodes.UNAUTHORIZED))
        assertEquals(SyncErrorKind.RESYNC, classifyApiErrorCode(ApiErrorCodes.RESYNC_REQUIRED))
        assertEquals(SyncErrorKind.BUSY, classifyApiErrorCode(ApiErrorCodes.TIMEOUT))
        assertEquals(SyncErrorKind.CONFLICT, classifyApiErrorCode(ApiErrorCodes.IDEMPOTENCY_CONFLICT))
        assertEquals(null, classifyApiErrorCode(null))
    }
}
