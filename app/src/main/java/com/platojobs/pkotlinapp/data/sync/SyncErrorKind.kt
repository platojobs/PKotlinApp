package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 同步失败分类。UI 只展示对应类别的可读文案，不直接把底层异常 message 拼给用户。
 */
enum class SyncErrorKind {
    NETWORK,
    TIMEOUT,
    AUTH,
    CONFLICT,
    SERVER,
    UNKNOWN,
}

fun classifySyncError(error: Throwable): SyncErrorKind = when {
    error is AuthExpiredException -> SyncErrorKind.AUTH
    error is SocketTimeoutException -> SyncErrorKind.TIMEOUT
    error is UnknownHostException || error is ConnectException -> SyncErrorKind.NETWORK
    error is HttpException -> when (error.code()) {
        401, 403 -> SyncErrorKind.AUTH
        409 -> SyncErrorKind.CONFLICT
        else -> SyncErrorKind.SERVER
    }
    // 其余 IO 异常（断网、连接中断等）统一归为网络问题。
    error is IOException -> SyncErrorKind.NETWORK
    else -> SyncErrorKind.UNKNOWN
}
