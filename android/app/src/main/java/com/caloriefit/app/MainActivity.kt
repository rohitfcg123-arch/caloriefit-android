package com.caloriefit.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var healthClient: HealthConnectClient? = null
    private val stepPermission = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(stepPermission)) readTodaySteps()
        else sendStepsError("Steps permission was not granted.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(StepsBridge(), "CalorieFitNative")
        setContentView(webView)
        webView.loadUrl("https://rohitfcg123-arch.github.io/caloriefit-android/")
    }

    inner class StepsBridge {
        @JavascriptInterface
        fun syncTodaySteps() {
            runOnUiThread {
                val status = HealthConnectClient.getSdkStatus(this@MainActivity)
                if (status != HealthConnectClient.SDK_AVAILABLE) {
                    sendStepsError("Health Connect is unavailable or needs an update.")
                    return@runOnUiThread
                }
                healthClient = HealthConnectClient.getOrCreate(this@MainActivity)
                lifecycleScope.launch {
                    try {
                        val granted = healthClient!!.permissionController.getGrantedPermissions()
                        if (!granted.containsAll(stepPermission)) permissionLauncher.launch(stepPermission)
                        else readTodaySteps()
                    } catch (e: Exception) {
                        sendStepsError("Could not check Health Connect permissions.")
                    }
                }
            }
        }
    }

    private fun readTodaySteps() {
        val client = healthClient ?: return
        lifecycleScope.launch {
            try {
                val zone = ZoneId.systemDefault()
                val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val end = Instant.now()
                val response = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
                webView.evaluateJavascript(
                    "window.calorieFitReceiveSteps && window.calorieFitReceiveSteps($steps)", null
                )
            } catch (e: Exception) {
                sendStepsError("Unable to read steps. Check Health Connect permissions.")
            }
        }
    }

    private fun sendStepsError(message: String) {
        val safe = JSONObject.quote(message)
        webView.evaluateJavascript(
            "window.calorieFitStepsError && window.calorieFitStepsError($safe)", null
        )
    }
}
