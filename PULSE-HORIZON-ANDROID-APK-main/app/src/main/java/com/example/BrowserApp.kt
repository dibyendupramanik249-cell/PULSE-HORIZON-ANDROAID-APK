package com.example

import android.app.Application
import java.io.File

class BrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Clean up any previously corrupted HTTP Cache directory injected by prior runs
            val badSubdir = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            if (badSubdir.exists()) {
                File(cacheDir, "WebView").deleteRecursively()
            }
            // Clean up unverified or corrupted Chromium variations seed files
            val appWebviewDir = File(dataDir, "app_webview")
            if (appWebviewDir.exists()) {
                appWebviewDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("variations_seed") || file.name.startsWith("variations-seed")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

