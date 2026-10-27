package com.tyust.course.academic.plugin

enum class PluginErrorCode {
    UNSUPPORTED, NOT_OPEN, INVALID_CREDENTIALS, SESSION_EXPIRED, CAPTCHA_REQUIRED, WEB_LOGIN_REQUIRED,
    NO_CAPACITY, CONFLICT, ALREADY_SELECTED, CREDIT_LIMIT, PAGE_CHANGED, NETWORK_RETRYABLE,
    RESULT_UNKNOWN, UNTRUSTED_URL, VALIDATION_FAILED, TIMEOUT, CANCELLED, RUNTIME_EXITED,
    RESOURCE_LIMIT, BAD_SIGNATURE, PERMISSION_DENIED, STALE_CONTEXT
}

class PluginException(val code: PluginErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        if (output.size() + n > limit) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "下载内容超过上限")
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
