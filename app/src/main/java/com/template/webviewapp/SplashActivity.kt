package com.template.webviewapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows the themed splash background instantly on cold start (avoids the
 * white-flash-then-webview problem). If SPLASH_ENABLED is false for this
 * app, we skip straight to MainActivity with no delay.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val delayMillis = if (BuildConfig.SPLASH_ENABLED) 900L else 0L

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, delayMillis)
    }
}
