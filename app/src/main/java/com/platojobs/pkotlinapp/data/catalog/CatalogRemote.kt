package com.platojobs.pkotlinapp.data.catalog

import com.platojobs.pkotlinapp.data.remote.ApiService
import com.platojobs.pkotlinapp.data.remote.DeviceTypeCatalogDto
import com.platojobs.pkotlinapp.domain.catalog.CatalogFailureReason
import java.io.IOException

/** 目录请求结果；304 与失败都保留本地缓存不动。 */
sealed interface CatalogFetchResult {
    /** 200：携带新目录与用于下次条件请求的校验器。 */
    data class Success(
        val catalog: DeviceTypeCatalogDto,
        val etag: String?,
        val lastModified: String?,
    ) : CatalogFetchResult

    /** 304：目录未变化，复用缓存并刷新使用时间。 */
    data object NotModified : CatalogFetchResult

    data class Failure(
        val reason: CatalogFailureReason,
        val httpCode: Int? = null,
        val detail: String? = null,
    ) : CatalogFetchResult
}

/** 把 Retrofit 细节隔离在远端适配里，仓库只关心 200 / 304 / 失败三种结果。 */
interface CatalogRemote {
    suspend fun fetch(etag: String?, lastModified: String?): CatalogFetchResult
}

class RetrofitCatalogRemote(private val api: ApiService) : CatalogRemote {

    override suspend fun fetch(etag: String?, lastModified: String?): CatalogFetchResult =
        try {
            val response = api.getDeviceTypeCatalog(
                ifNoneMatch = etag,
                ifModifiedSince = lastModified,
            )
            when {
                // 304 不是 2xx，必须先判；此时 body 为空，直接复用缓存。
                response.code() == HTTP_NOT_MODIFIED -> CatalogFetchResult.NotModified
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        CatalogFetchResult.Failure(
                            reason = CatalogFailureReason.EMPTY,
                            httpCode = response.code(),
                        )
                    } else {
                        CatalogFetchResult.Success(
                            catalog = body,
                            etag = response.headers()["ETag"],
                            lastModified = response.headers()["Last-Modified"],
                        )
                    }
                }
                else -> CatalogFetchResult.Failure(
                    reason = CatalogFailureReason.HTTP,
                    httpCode = response.code(),
                    detail = response.errorBody()?.string()?.take(MAX_ERROR_DETAIL),
                )
            }
        } catch (e: IOException) {
            CatalogFetchResult.Failure(
                reason = CatalogFailureReason.NETWORK,
                detail = e.message,
            )
        } catch (e: Exception) {
            CatalogFetchResult.Failure(
                reason = CatalogFailureReason.UNKNOWN,
                detail = e.message,
            )
        }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
        const val MAX_ERROR_DETAIL = 300
    }
}
