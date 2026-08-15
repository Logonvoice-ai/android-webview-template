package com.template.webviewapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.activity.OnBackPressedCallback

/**
 * Hosts the WebView and owns everything Activity-lifecycle-shaped:
 * permission requests, connectivity state, back-button navigation, and
 * pull-to-refresh. WebView configuration/behavior itself lives in
 * WebViewController so this class stays readable.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: LinearLayout
    private lateinit var connectivityBanner: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var trialBanner: TextView
    private lateinit var lockView: LinearLayout
    private lateinit var webViewController: WebViewController

    // Tracks whether we're currently showing EITHER the full offline screen
    // or the small banner, so onAvailable() only reloads when there was
    // actually something to recover from -- without this, every network
    // interface change (even harmless ones, e.g. switching wifi bands)
    // would trigger a reload.
    private var isCurrentlyOffline = false

    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // --- Runtime permission plumbing -------------------------------------
    // WebViewController calls these when the web page actually needs the
    // capability (e.g. <input type=file capture>, geolocation API).
    // Whether we even attempt the request is gated by BuildConfig, which
    // reflects the toggles the app owner set when generating the app.
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermissionCallback?.invoke(granted)
        pendingPermissionCallback = null
    }

    // --- File chooser plumbing (for <input type="file"> in the web page) --
    private var pendingFileChooserCallback: ((Array<Uri>?) -> Unit)? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        pendingFileChooserCallback?.invoke(uris)
        pendingFileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineView = findViewById(R.id.offlineView)
        connectivityBanner = findViewById(R.id.connectivityBanner)
        progressBar = findViewById(R.id.progressBar)
        trialBanner = findViewById(R.id.trialBanner)
        lockView = findViewById(R.id.lockView)

        findViewById<Button>(R.id.unlockButton).setOnClickListener {
            TrialGate.checkFresh(this) { result ->
                // Re-fetch right before opening the browser too, in case
                // they'd already paid since the app was last opened.
                TrialGate.openUnlockPage(this, result ?: return@checkFresh)
            }
        }
        findViewById<Button>(R.id.recheckLicenseButton).setOnClickListener {
            TrialGate.checkFresh(this) { result -> applyLicenseState(result) }
        }

        webViewController = WebViewController(
            activity = this,
            webView = webView,
            progressBar = progressBar,
            onOfflineStateChanged = ::setOfflineState,
            onConnectivityBannerChanged = ::setConnectivityBanner,
            requestRuntimePermission = ::requestRuntimePermission,
            launchFileChooser = ::launchFileChooser
        )
        webViewController.setup(startUrl = BuildConfig.START_URL)

        // Checked async so it never delays the page load itself -- the
        // common case (already licensed, or still within trial) just
        // shows nothing. Only an expired-and-unpaid result changes what's
        // on screen.
        TrialGate.check(this) { result -> applyLicenseState(result) }

        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                setOfflineState(true)
            }
        }

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isNetworkAvailable()) {
                setOfflineState(false)
                webView.reload()
            }
        }

        registerConnectivityCallback()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where payment succeeded while the app was in
        // the background -- e.g. they paid in the browser opened from the
        // lock screen, then switched back to this app.
        TrialGate.check(this) { result -> applyLicenseState(result) }
    }

    /**
     * result == null means "couldn't determine license state" (offline,
     * unlicensed manual test build, etc.) -- fail open, change nothing.
     */
    private fun applyLicenseState(result: TrialGate.LicenseResult?) {
        if (result == null) return

        lockView.visibility = if (result.trialExpired && !result.licensed) android.view.View.VISIBLE else android.view.View.GONE

        trialBanner.visibility = if (result.isTrial && !result.trialExpired) android.view.View.VISIBLE else android.view.View.GONE
        if (result.isTrial) {
            trialBanner.text = if (result.trialDaysRemaining > 1) {
                getString(R.string.trial_banner_days_remaining, result.trialDaysRemaining)
            } else {
                getString(R.string.trial_banner_last_day)
            }
        }
    }

    private fun setOfflineState(offline: Boolean) {
        offlineView.visibility = if (offline) android.view.View.VISIBLE else android.view.View.GONE
        swipeRefresh.visibility = if (offline) android.view.View.GONE else android.view.View.VISIBLE
        swipeRefresh.isRefreshing = false
        isCurrentlyOffline = offline
    }

    /**
     * The small top banner used when connectivity drops AFTER a page has
     * already loaded successfully -- keeps the last-loaded content on
     * screen instead of replacing it, unlike setOfflineState() which is
     * reserved for a cold-start failure.
     */
    private fun setConnectivityBanner(offline: Boolean) {
        connectivityBanner.visibility = if (offline) android.view.View.VISIBLE else android.view.View.GONE
        isCurrentlyOffline = offline
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerConnectivityCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread { setOfflineState(true) }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    // Only act if we were actually showing an offline state --
                    // otherwise this fires on routine network changes (e.g.
                    // wifi roaming between access points) and would reload
                    // the page unnecessarily.
                    if (isCurrentlyOffline) {
                        setOfflineState(false)
                        setConnectivityBanner(false)
                        webView.reload()
                    }
                }
            }
        })

        if (!isNetworkAvailable()) {
            setOfflineState(true)
        }
    }

    /**
     * Central runtime-permission gate used by WebViewController. Checks the
     * BuildConfig toggle first (the app owner's declared intent), then the
     * actual OS permission state, requesting it if needed.
     */
    private fun requestRuntimePermission(permission: String, callback: (Boolean) -> Unit) {
        val allowedByAppConfig = when (permission) {
            Manifest.permission.CAMERA -> BuildConfig.PERMISSION_CAMERA
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> BuildConfig.PERMISSION_LOCATION
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> BuildConfig.PERMISSION_STORAGE
            else -> false
        }

        if (!allowedByAppConfig) {
            callback(false)
            return
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            callback(true)
            return
        }

        pendingPermissionCallback = callback
        permissionLauncher.launch(permission)
    }

    private fun launchFileChooser(intent: Intent, callback: (Array<Uri>?) -> Unit) {
        pendingFileChooserCallback = callback
        fileChooserLauncher.launch(intent)
    }
}
