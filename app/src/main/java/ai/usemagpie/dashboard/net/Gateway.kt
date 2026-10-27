package ai.usemagpie.dashboard.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Result of validating user input for the gateway地址 field. */
sealed class UrlCheck {
    data class Ok(val base: HttpUrl) : UrlCheck()
    data class Err(val reason: String) : UrlCheck()
}

sealed class GatewayError {
    object NotConfigured : GatewayError()
    data class InvalidUrl(val reason: String) : GatewayError()
    object Unauthorized : GatewayError()
    object Forbidden : GatewayError()
    object NotFound : GatewayError()
    data class Http(val code: Int, val message: String?) : GatewayError()
    data class Connection(val message: String) : GatewayError()
    object Timeout : GatewayError()
    data class Tls(val message: String) : GatewayError()
    data class Malformed(val message: String) : GatewayError()
}

sealed class GatewayResult {
    data class Ok(val body: String) : GatewayResult()
    data class Err(val error: GatewayError) : GatewayResult()
}

data class ConnectionSettings(val base: HttpUrl, val apiKey: String) {
    fun url(vararg segments: String): HttpUrl {
        val b = base.newBuilder()
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    fun url(pathSegments: List<String>, query: List<Pair<String, String>>): HttpUrl {
        val b = base.newBuilder()
        pathSegments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }
}

/**
 * Gateway address rules:
 * - http:// or https:// with host (port optional, proxy path prefix preserved)
 * - no userinfo, no query, no fragment
 * - trailing slashes dropped, a trailing "/v1" segment dropped so that our own "/v1/..." is not doubled
 */
object UrlRules {
    fun normalize(raw: String): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck.Err("请输入网关地址")
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return UrlCheck.Err("地址需以 http:// 或 https:// 开头")
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return UrlCheck.Err("地址格式无效")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return UrlCheck.Err("地址不能包含用户名或密码")
        }
        if (parsed.query != null) return UrlCheck.Err("地址不能包含查询参数")
        if (parsed.fragment != null) return UrlCheck.Err("地址不能包含 # 片段")
        if (parsed.host.isBlank()) return UrlCheck.Err("地址缺少主机名")

        var path = parsed.encodedPath.trimEnd('/')
        if (path.endsWith("/v1")) path = path.dropLast(3).trimEnd('/')
        val base = parsed.newBuilder()
            .encodedPath(if (path.isEmpty()) "/" else path)
            .build()
        return UrlCheck.Ok(base)
    }

    fun validateKey(key: String): String? = if (key.trim().isEmpty()) "请输入 API key" else null
}

/** All read-only GET endpoints this app consumes. */
object Endpoints {
    const val USAGE = "usage"
    const val REQUESTS = "requests"
    const val QUOTAS = "quotas"
    const val QUOTA_CARDS = "cards"
    const val QUOTA_HISTORY = "history"
    const val CONCURRENCY = "concurrency"
    const val ROUTE = "route"

    fun segments(endpoint: String): List<String> = when (endpoint) {
        QUOTA_CARDS -> listOf("v1", "magpie", "quotas", "cards")
        QUOTA_HISTORY -> listOf("v1", "magpie", "quotas", "history")
        REQUESTS -> listOf("v1", "magpie", "usage", "requests")
        else -> listOf("v1", "magpie", endpoint)
    }
}

class GatewayClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun get(settings: ConnectionSettings, endpoint: String, query: List<Pair<String, String>> = emptyList()): GatewayResult {
        val url = settings.url(Endpoints.segments(endpoint), query)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> if (body.isBlank()) {
                        GatewayResult.Err(GatewayError.Malformed("响应为空"))
                    } else {
                        GatewayResult.Ok(body)
                    }
                    401 -> GatewayResult.Err(GatewayError.Unauthorized)
                    403 -> GatewayResult.Err(GatewayError.Forbidden)
                    404 -> GatewayResult.Err(GatewayError.NotFound)
                    else -> GatewayResult.Err(GatewayError.Http(response.code, errorMessageOf(body)))
                }
            }
        } catch (e: SocketTimeoutException) {
            GatewayResult.Err(GatewayError.Timeout)
        } catch (e: SSLException) {
            GatewayResult.Err(GatewayError.Tls(e.message ?: "证书校验失败"))
        } catch (e: UnknownHostException) {
            GatewayResult.Err(GatewayError.Connection("无法解析主机名"))
        } catch (e: IOException) {
            GatewayResult.Err(GatewayError.Connection(e.message ?: "连接失败"))
        }
    }

    private fun errorMessageOf(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = org.json.JSONObject(trimmed)
            obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: obj.optString("message").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
