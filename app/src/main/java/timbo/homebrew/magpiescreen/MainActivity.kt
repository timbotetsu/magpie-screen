package timbo.homebrew.magpiescreen

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import timbo.homebrew.magpiescreen.data.AppPrefs
import timbo.homebrew.magpiescreen.databinding.ActivityMainBinding
import timbo.homebrew.magpiescreen.net.ConnectionSettings
import timbo.homebrew.magpiescreen.net.GatewayClient
import timbo.homebrew.magpiescreen.net.UrlCheck
import timbo.homebrew.magpiescreen.net.UrlRules
import timbo.homebrew.magpiescreen.ui.OverviewFragment
import timbo.homebrew.magpiescreen.ui.QuotasFragment
import timbo.homebrew.magpiescreen.ui.RequestsFragment
import timbo.homebrew.magpiescreen.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var prefs: AppPrefs
        private set
    val gateway = GatewayClient()

    private var currentTab: String = TAB_OVERVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        binding.tabOverview.setOnClickListener { select(TAB_OVERVIEW) }
        binding.tabRequests.setOnClickListener { select(TAB_REQUESTS) }
        binding.tabQuotas.setOnClickListener { select(TAB_QUOTAS) }
        binding.tabSettings.setOnClickListener { select(TAB_SETTINGS) }

        val restored = savedInstanceState?.getString(KEY_TAB)
        val start = when {
            restored != null -> restored
            connection() == null -> TAB_SETTINGS
            else -> TAB_OVERVIEW
        }
        select(start, force = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    /** Null until both a valid address and a non-empty key are stored. */
    fun connection(): ConnectionSettings? {
        val rawUrl = prefs.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = prefs.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val check = UrlRules.normalize(rawUrl)
        val ok = check as? UrlCheck.Ok ?: return null
        return ConnectionSettings(ok.base, key.trim())
    }

    fun storeConnection(rawUrl: String, key: String) {
        prefs.baseUrl = rawUrl.trim()
        prefs.apiKey = key.trim()
    }

    fun select(tab: String, force: Boolean = false) {
        if (!force && tab == currentTab) return
        currentTab = tab
        val fragment: Fragment = when (tab) {
            TAB_REQUESTS -> RequestsFragment()
            TAB_QUOTAS -> QuotasFragment()
            TAB_SETTINGS -> SettingsFragment()
            else -> OverviewFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment, tab)
            .commit()
        applyTabColors()
    }

    fun openSettings() = select(TAB_SETTINGS)

    private fun applyTabColors() {
        val map = mapOf(
            TAB_OVERVIEW to binding.tabOverview,
            TAB_REQUESTS to binding.tabRequests,
            TAB_QUOTAS to binding.tabQuotas,
            TAB_SETTINGS to binding.tabSettings,
        )
        map.forEach { (tag, view) ->
            view.setTextColor(getColor(if (tag == currentTab) R.color.accent else R.color.muted))
        }
    }

    companion object {
        const val TAB_OVERVIEW = "overview"
        const val TAB_REQUESTS = "requests"
        const val TAB_QUOTAS = "quotas"
        const val TAB_SETTINGS = "settings"
        private const val KEY_TAB = "tab"
    }
}
