package timbo.homebrew.magpiescreen.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import timbo.homebrew.magpiescreen.data.Quotas
import timbo.homebrew.magpiescreen.data.RequestsPage
import timbo.homebrew.magpiescreen.data.UsageSnapshot
import timbo.homebrew.magpiescreen.databinding.FragmentSettingsBinding
import timbo.homebrew.magpiescreen.net.ConnectionSettings
import timbo.homebrew.magpiescreen.net.Endpoints
import timbo.homebrew.magpiescreen.net.GatewayResult
import timbo.homebrew.magpiescreen.net.UrlCheck
import timbo.homebrew.magpiescreen.net.UrlRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : BaseFragment() {

    private var binding: FragmentSettingsBinding? = null
    private var keyVisible = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = b
        b.inputUrl.setText(app.prefs.baseUrl.orEmpty())
        b.inputKey.setText(app.prefs.apiKey.orEmpty())
        b.toggleKey.setOnClickListener { toggleKeyVisibility() }
        b.btnTest.setOnClickListener { saveAndTest() }
        b.capabilities.text = "尚未测试"
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun toggleKeyVisibility() {
        val b = binding ?: return
        keyVisible = !keyVisible
        b.inputKey.inputType = if (keyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        b.toggleKey.text = if (keyVisible) "隐藏" else "显示"
        b.inputKey.setSelection(b.inputKey.text.length)
    }

    private fun saveAndTest() {
        val b = binding ?: return
        val rawUrl = b.inputUrl.text.toString()
        val rawKey = b.inputKey.text.toString().trim()

        val urlCheck = UrlRules.normalize(rawUrl)
        if (urlCheck is UrlCheck.Err) {
            b.inputUrl.error = urlCheck.reason
            b.status.text = "地址错误：${urlCheck.reason}"
            b.capabilities.text = "尚未测试"
            return
        }
        b.inputUrl.error = null
        val keyProblem = UrlRules.validateKey(rawKey)
        if (keyProblem != null) {
            b.inputKey.error = keyProblem
            b.status.text = keyProblem
            b.capabilities.text = "尚未测试"
            return
        }
        b.inputKey.error = null

        val base = (urlCheck as UrlCheck.Ok).base
        app.storeConnection(base.toString(), rawKey)
        val settings = ConnectionSettings(base, rawKey)

        b.btnTest.isEnabled = false
        b.status.text = "测试中…"
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) {
                app.gateway.get(settings, Endpoints.USAGE, listOf("period" to "today"))
            }
            val quotas = withContext(Dispatchers.IO) {
                app.gateway.get(settings, Endpoints.QUOTAS, listOf("kept" to "1"))
            }
            val concurrency = withContext(Dispatchers.IO) { app.gateway.get(settings, Endpoints.CONCURRENCY) }
            val requests = withContext(Dispatchers.IO) {
                app.gateway.get(settings, Endpoints.REQUESTS, listOf("period" to "today", "offset" to "0", "limit" to "1"))
            }
            val view = binding ?: return@launch
            view.btnTest.isEnabled = true

            val usageSnapshot = (usage as? GatewayResult.Ok)?.let { r ->
                runCatching { UsageSnapshot.parse(r.body) }.getOrNull()
            }
            val lines = ArrayList<String>()
            lines += if (usageSnapshot != null) {
                "网关统计：可用 · 今日 ${Fmt.raw(usageSnapshot.stats.calls)} 次调用 · 总 Token ${Fmt.compact(usageSnapshot.stats.tokens.total)}"
            } else {
                "网关统计：${describeOrInvalid(usage)}"
            }
            lines += if (quotas is GatewayResult.Ok) {
                val cards = runCatching { Quotas.parseCards(quotas.body) }.getOrNull()
                "额度：可用 · ${cards?.size ?: 0} 张已缓存卡片"
            } else {
                "额度：${describeOrInvalid(quotas)}"
            }
            lines += if (concurrency is GatewayResult.Ok) {
                "实时并发：可用"
            } else {
                "实时并发：${describeOrInvalid(concurrency)}"
            }
            lines += if (requests is GatewayResult.Ok) {
                val page = runCatching { RequestsPage.parse(requests.body) }.getOrNull()
                "请求明细：可用 · 今日记录 ${page?.total ?: 0} 条"
            } else {
                "请求明细：${describeOrInvalid(requests)}"
            }
            view.capabilities.text = lines.joinToString("\n")

            view.status.text = if (usageSnapshot != null) {
                "网关统计可用 · ${Fmt.clock(System.currentTimeMillis())}"
            } else {
                "网关统计不可用"
            }
        }
    }

    private fun describeOrInvalid(result: GatewayResult): String =
        if (result is GatewayResult.Err) describe(result) else "响应不可解析"
}
