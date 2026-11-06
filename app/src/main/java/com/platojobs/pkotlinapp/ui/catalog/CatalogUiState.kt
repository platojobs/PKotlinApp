package com.platojobs.pkotlinapp.ui.catalog

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.catalog.CatalogRefreshResult
import com.platojobs.pkotlinapp.domain.catalog.CatalogFailureReason
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import java.text.DateFormat
import java.util.Date

/**
 * 目录页面共用状态：
 * - 有缓存立即展示，刷新失败只做提示，不清空内容；
 * - 无缓存且失败才进入错误态，绝不用写死清单冒充服务端数据。
 */
sealed interface CatalogUiState {
    data object Loading : CatalogUiState

    data class Content(
        val snapshot: DeviceTypeCatalog,
        val refreshFailure: CatalogRefreshResult.Failed? = null,
    ) : CatalogUiState

    data class Error(val failure: CatalogRefreshResult.Failed?) : CatalogUiState
}

internal fun catalogUiState(
    snapshot: DeviceTypeCatalog,
    failure: CatalogRefreshResult.Failed?,
): CatalogUiState = when {
    snapshot.hasCache -> CatalogUiState.Content(snapshot, failure)
    failure != null -> CatalogUiState.Error(failure)
    else -> CatalogUiState.Loading
}

/** 目录更新时间：服务端 `generatedAt` 按本地时区展示；解析失败时原样展示，不伪造时间。 */
internal fun catalogUpdatedAt(context: Context, snapshot: DeviceTypeCatalog): String? {
    val raw = snapshot.generatedAt?.takeIf { it.isNotBlank() } ?: return null
    val millis = Rfc3339.parse(raw)
    return if (millis == null) {
        raw
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(millis))
    }
}

internal fun failureText(context: Context, failure: CatalogRefreshResult.Failed): String {
    val base = when (failure.reason) {
        CatalogFailureReason.NETWORK -> context.getString(R.string.catalog_error_network)
        CatalogFailureReason.EMPTY -> context.getString(R.string.catalog_error_empty)
        CatalogFailureReason.HTTP -> failure.httpCode?.let { code ->
            context.getString(R.string.catalog_error_http, code)
        } ?: context.getString(R.string.catalog_error_unknown)
        CatalogFailureReason.UNKNOWN -> context.getString(R.string.catalog_error_unknown)
    }
    val detail = failure.detail?.takeIf { it.isNotBlank() }
    return if (detail == null) base else context.getString(R.string.catalog_error_detail, base, detail)
}

/** 有缓存时的状态行：离线提示优先，其次展示目录更新时间。 */
internal fun catalogStatusText(
    context: Context,
    state: CatalogUiState.Content,
): String? {
    val updatedAt = catalogUpdatedAt(context, state.snapshot)
    return when {
        state.refreshFailure != null && updatedAt != null ->
            context.getString(R.string.catalog_offline_cache, updatedAt)

        state.refreshFailure != null -> context.getString(R.string.catalog_offline_cache_no_time)
        updatedAt != null -> context.getString(R.string.catalog_updated_at, updatedAt)
        else -> null
    }
}
