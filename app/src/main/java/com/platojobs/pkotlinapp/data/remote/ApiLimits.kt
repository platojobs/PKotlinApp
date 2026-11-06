package com.platojobs.pkotlinapp.data.remote

/**
 * 服务端列表分页上限（openapi）：readings 500、export 5000、changes 500。
 *
 * 客户端在发起请求前自行收敛，超限前分片，避免拿到 400 `BAD_REQUEST`。
 */
object ApiLimits {
    const val READINGS_MAX = 500
    const val EXPORT_MAX = 5000
    const val CHANGES_MAX = 500
    const val FEEDBACK_PAGE_MAX = 100
}
