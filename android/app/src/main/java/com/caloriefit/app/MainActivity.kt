package com.caloriefit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var healthClient: HealthConnectClient? = null
    private var pendingWalkStart = false
    private val stepPermission = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(stepPermission)) readTodaySteps()
        else sendStepsError("Steps permission was not granted.")
    }

    private val walkingPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            grants[Manifest.permission.ACTIVITY_RECOGNITION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (pendingWalkStart && activityGranted && locationGranted) startWalkingService()
        else sendWalkError("Walking needs physical activity and location permissions. Please allow both.")
        pendingWalkStart = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        window.statusBarColor = android.graphics.Color.rgb(246, 249, 247)
        window.navigationBarColor = android.graphics.Color.WHITE
        // Use dark system-bar icons on the light CalorieFit background.
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
                val status = HealthConnectClient.getSdkStatus(this@MainActivity, "com.google.android.apps.healthdata")
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

        @JavascriptInterface
        fun startWalking() {
            runOnUiThread {
                val needed = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.isNotEmpty()) {
                    pendingWalkStart = true
                    walkingPermissionsLauncher.launch(needed.toTypedArray())
                } else {
                    startWalkingService()
                }
            }
        }

        @JavascriptInterface
        fun stopWalking() {
            runOnUiThread {
                val intent = Intent(this@MainActivity, WalkingTrackingService::class.java).setAction(WalkingTrackingService.ACTION_STOP)
                startService(intent)
                sendWalkStatus()
            }
        }

        @JavascriptInterface
        fun getWalkingStatus() {
            runOnUiThread { sendWalkStatus() }
        }
    }

    private fun startWalkingService() {
        val intent = Intent(this, WalkingTrackingService::class.java).setAction(WalkingTrackingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        webView.postDelayed({ sendWalkStatus() }, 500)
    }

    private fun sendWalkStatus() {
        val prefs = getSharedPreferences("caloriefit_walking", MODE_PRIVATE)
        val route = try { JSONArray(prefs.getString("route", "[]")) } catch (_: Exception) { JSONArray() }
        val data = JSONObject()
            .put("active", prefs.getBoolean("active", false))
            .put("steps", prefs.getLong("steps", 0L))
            .put("distanceM", prefs.getFloat("distance_m", 0f).toDouble())
            .put("startMs", prefs.getLong("start_ms", 0L))
            .put("route", route)
        webView.evaluateJavascript("window.calorieFitWalkStatus && window.calorieFitWalkStatus(${JSONObject.quote(data.toString())})", null)
    }

    private fun sendWalkError(message: String) {
        webView.evaluateJavascript("window.calorieFitWalkError && window.calorieFitWalkError(${JSONObject.quote(message)})", null)
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
                webView.evaluateJavascript("window.calorieFitReceiveSteps && window.calorieFitReceiveSteps($steps)", null)
            } catch (e: Exception) {
                sendStepsError("Unable to read steps. Check Health Connect permissions.")
            }
        }
    }

    private fun sendStepsError(message: String) {
        webView.evaluateJavascript("window.calorieFitStepsError && window.calorieFitStepsError(${JSONObject.quote(message)})", null)
    }
}
