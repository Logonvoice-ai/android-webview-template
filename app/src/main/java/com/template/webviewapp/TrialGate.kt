package com.template.webviewapp

// -----------------------------------------------------------------------
// TrialGate.kt
//
// Calls the backend's public license check (GET /api/license/{license_key})
// so this specific compiled build knows whether it's still in its trial
// window, has expired, or belongs to a project that's already been paid
// for. LICENSE_KEY and API_BASE come from BuildConfig, injected per-build
// via gradle.properties -- see app/build.gradle.
//
// Wired into MainActivity.onCreate() (before webViewController.setup) and
// onResume(); see MainActivity.kt.
// -----------------------------------------------------------------------

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TrialGate {

    private const val PREFS = "trial_gate_prefs"
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24h -- see checkFresh() for bypassing this

    data class LicenseResult(
        val licensed: Boolean,
        val isTrial: Boolean,
        val trialDaysRemaining: Int,
        val trialExpired: Boolean,
        val unlockUrl: String?
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fail-open by design: if BuildConfig.LICENSE_KEY/API_BASE are blank
     * (e.g. a manual workflow_dispatch test build with no matching Build
     * row), or the request fails for any reason (no internet, DNS, server
     * hiccup), the callback receives null and the caller should just show
     * the site normally -- a connectivity problem should never lock out a
     * paying (or still-in-trial) user.
     */
    fun check(context: Context, callback: (LicenseResult?) -> Unit) {
        if (BuildConfig.LICENSE_KEY.isBlank() || BuildConfig.API_BASE.isBlank()) {
            callback(null)
            return
        }

        val cached = readCache(context)
        if (cached != null) {
            mainHandler.post { callback(cached) }
        }

        executor.execute {
            val fresh = fetch()
            if (fresh != null) {
                writeCache(context, fresh)
                if (cached == null) mainHandler.post { callback(fresh) }
            } else if (cached == null) {
                mainHandler.post { callback(null) }
            }
        }
    }

    /**
     * Bypasses the cache entirely. Wire this to the lock screen's
     * "I've already paid -- check again" button -- without it, someone
     * who just paid could be stuck on the lock screen up to 24h since the
     * cached "expired" result wouldn't refresh that soon on its own.
     */
    fun checkFresh(context: Context, callback: (LicenseResult?) -> Unit) {
        if (BuildConfig.LICENSE_KEY.isBlank() || BuildConfig.API_BASE.isBlank()) {
            callback(null)
            return
        }
        executor.execute {
            val fresh = fetch()
            if (fresh != null) writeCache(context, fresh)
            mainHandler.post { callback(fresh) }
        }
    }

    private fun fetch(): LicenseResult? {
        return try {
            val url = URL("${BuildConfig.API_BASE}/api/license/${BuildConfig.LICENSE_KEY}")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            LicenseResult(
                licensed = json.getBoolean("licensed"),
                isTrial = json.getBoolean("is_trial"),
                trialDaysRemaining = json.optInt("trial_days_remaining", 0),
                trialExpired = json.getBoolean("trial_expired"),
                unlockUrl = json.optString("unlock_url", null)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readCache(context: Context): LicenseResult? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("saved_at", 0)
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) return null

        return LicenseResult(
            licensed = prefs.getBoolean("licensed", true),
            isTrial = prefs.getBoolean("is_trial", false),
            trialDaysRemaining = prefs.getInt("trial_days_remaining", 0),
            trialExpired = prefs.getBoolean("trial_expired", false),
            unlockUrl = prefs.getString("unlock_url", null)
        )
    }

    private fun writeCache(context: Context, result: LicenseResult) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("saved_at", System.currentTimeMillis())
            .putBoolean("licensed", result.licensed)
            .putBoolean("is_trial", result.isTrial)
            .putInt("trial_days_remaining", result.trialDaysRemaining)
            .putBoolean("trial_expired", result.trialExpired)
            .putString("unlock_url", result.unlockUrl)
            .apply()
    }

    fun openUnlockPage(context: Context, result: LicenseResult) {
        val url = result.unlockUrl ?: "${BuildConfig.API_BASE}/unlock/${BuildConfig.LICENSE_KEY}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
