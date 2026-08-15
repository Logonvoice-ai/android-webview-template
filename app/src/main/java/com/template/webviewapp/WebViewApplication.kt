package com.template.webviewapp

import android.app.Application

/**
 * Deliberately minimal. This is the seam where future plugins (push
 * notifications, crash reporting, analytics) get initialized without
 * touching MainActivity or WebViewController.
 */
class WebViewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
