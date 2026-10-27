package timbo.homebrew.magpiescreen.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import timbo.homebrew.magpiescreen.MainActivity
import timbo.homebrew.magpiescreen.net.GatewayError
import timbo.homebrew.magpiescreen.net.GatewayResult
import timbo.homebrew.magpiescreen.net.ResponseCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared fragment behaviour: a dashboard that keeps itself current while it is on screen.
 *
 * - every [CHECK_INTERVAL_MS] the page checks its cache and only requests data older than
 *   [ResponseCache.DEFAULT_TTL_MS] (unless the page overrides [onRefreshTick] to do nothing,
 *   as the settings form does);
 * - coming back to the foreground runs a cache check at once, going away stops the timer;
 * - the screen is kept on while any page is in the foreground, so a wall-mounted device does
 *   not fall asleep and freeze on stale numbers.
 */
abstract class BaseFragment : Fragment() {

    protected val app: MainActivity get() = requireActivity() as MainActivity

    private var ticker: Job? = null
    private var startedBefore = false

    override fun onStart() {
        super.onStart()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (startedBefore) onRefreshTick() else startedBefore = true
        ticker?.cancel()
        ticker = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                onRefreshTick()
            }
        }
    }

    override fun onStop() {
        ticker?.cancel()
        ticker = null
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    /** Called by the timer and when the page returns to the foreground. */
    protected open fun onRefreshTick() {}

    /** User-facing text for a failed call; never includes the key or the URL credentials. */
    protected fun describe(result: GatewayResult.Err): String = when (val e = result.error) {
        GatewayError.NotConfigured -> "未配置网关地址或 API key"
        is GatewayError.InvalidUrl -> "地址无效：${e.reason}"
        GatewayError.Unauthorized -> "认证失败（401）：API key 无效"
        GatewayError.Forbidden -> "该 key 无权读取此数据（403）"
        GatewayError.NotFound -> "当前网关版本不支持该接口（404）"
        is GatewayError.Http -> "请求失败（HTTP ${e.code}）${e.message?.let { "：$it" } ?: ""}"
        is GatewayError.Connection -> "连接失败：${e.message}"
        GatewayError.Timeout -> "连接超时"
        is GatewayError.Tls -> "证书校验失败：${e.message}"
        is GatewayError.Malformed -> "响应不可解析：${e.message}"
    }

    protected companion object {
        /** Cache checks are cheap; the 5-minute freshness rule lives in [ResponseCache]. */
        const val CHECK_INTERVAL_MS = 60_000L
    }
}
