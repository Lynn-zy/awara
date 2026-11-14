package me.rerere.awara.data.repo

import retrofit2.HttpException
import retrofit2.Response

/** 校验空响应体请求是否成功；非 2xx 时抛出 [HttpException] 供上层统一处理。 */
internal fun Response<Unit>.requireSuccess() {
    if (!isSuccessful) throw HttpException(this)
}

/** 校验空响应体请求是否成功；非 2xx 时抛出携带明确业务提示的 [IllegalStateException]。 */
internal fun Response<Unit>.requireSuccess(failureMessage: () -> String) {
    check(isSuccessful, failureMessage)
}
