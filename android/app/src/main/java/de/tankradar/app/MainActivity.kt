package de.tankradar.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import de.tankradar.app.databinding.ActivityMainBinding

/**
 * Hosts the TankRadar dashboard in a WebView.
 *
 * TankRadar's UI is a Dash application, so the Android app is a thin, purpose-built
 * client for the server the user runs: it adds a launcher icon, pull-to-refresh,
 * hardware back navigation, a readable offline state and working file downloads.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverUrl: String? = null
    private var loadFailed = false

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whatever the user did in the setup screen, re-read the stored address:
        // cancelling out of it should keep the previous server, not clear it.
        loadConfiguredServer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        configureWebView()

        binding.swipeRefresh.setOnRefreshListener { reload() }
        binding.errorRetry.setOnClickListener { reload() }
        binding.errorChangeServer.setOnClickListener { openSetup() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            serverUrl = ServerConfig.getServerUrl(this)
        } else {
            loadConfiguredServer()
        }
    }

    private fun configureWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The dashboard ships its own responsive layout and viewport meta tag,
            // so the WebView must not add a desktop-width viewport on top of it.
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Nothing in the dashboard reads local files; keep the surface small.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }

        // The dashboard is dark-only; stop the WebView from inverting it again.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        addJavascriptInterface(
            DownloadBridge(applicationContext) { fileName, success ->
                runOnUiThread {
                    val message = if (success) {
                        getString(R.string.download_saved, fileName)
                    } else {
                        getString(R.string.download_failed, fileName)
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            },
            DownloadBridge.BRIDGE_NAME,
        )

        // Regular server-side downloads still go through the system download manager.
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startSystemDownload(url, userAgent, contentDisposition, mimeType)
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url.toString()
                val base = serverUrl ?: return false
                // Keep the TankRadar instance itself in the app and hand anything
                // else (support links, maps) to the user's browser.
                if (target.startsWith(base, ignoreCase = true)) return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }.getOrDefault(false)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (loadFailed) {
                    showError()
                } else {
                    showContent()
                    view?.evaluateJavascript(DownloadBridge.BLOB_DOWNLOAD_HOOK_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // Sub-resource failures (a single icon, a cancelled poll) must not
                // replace a working dashboard with the offline screen.
                if (request.isForMainFrame) {
                    loadFailed = true
                    showError()
                }
            }
        }
    }

    private fun startSystemDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val started = runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.isSuccess

        if (!started) {
            Toast.makeText(this, R.string.download_failed_generic, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadConfiguredServer() {
        val url = ServerConfig.getServerUrl(this)
        if (url == null) {
            openSetup()
            return
        }
        serverUrl = url
        loadFailed = false
        binding.webView.loadUrl(url)
    }

    private fun reload() {
        val url = serverUrl
        if (url == null) {
            binding.swipeRefresh.isRefreshing = false
            openSetup()
            return
        }
        loadFailed = false
        showContent()
        // After a failed load the WebView holds an error page rather than the
        // dashboard, so reload() alone would have nothing to reload.
        binding.webView.loadUrl(url)
    }

    private fun openSetup() {
        setupLauncher.launch(Intent(this, SetupActivity::class.java))
    }

    private fun showError() {
        binding.swipeRefresh.isRefreshing = false
        binding.errorView.visibility = View.VISIBLE
        binding.errorAddress.text = getString(
            R.string.error_address,
            serverUrl ?: getString(R.string.error_address_unknown),
        )
        binding.swipeRefresh.visibility = View.GONE
    }

    private fun showContent() {
        binding.errorView.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            reload()
            true
        }
        R.id.action_settings -> {
            openSetup()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
