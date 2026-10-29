package com.caloriefit.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class WalkingTrackingService : Service(), SensorEventListener, LocationListener {
    companion object {
        const val ACTION_START = "com.caloriefit.app.walk.START"
        const val ACTION_STOP = "com.caloriefit.app.walk.STOP"
        const val ACTION_REFRESH = "com.caloriefit.app.walk.REFRESH"
        private const val CHANNEL_ID = "caloriefit_walking"
        private const val NOTIFICATION_ID = 4107
        private const val PREFS = "caloriefit_walking"
    }

    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var lastLocation: Location? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_START -> startTracking()
            ACTION_REFRESH -> updateNotification()
            null -> if (prefs.getBoolean("active", false)) {
                startForeground(NOTIFICATION_ID, notification())
                registerTrackers()
            }
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (!prefs.getBoolean("active", false)) {
            prefs.edit()
                .putBoolean("active", true)
                .putLong("start_ms", System.currentTimeMillis())
                .putLong("steps", 0L)
                .putFloat("distance_m", 0f)
                .putFloat("last_counter", -1f)
                .putFloat("last_lat", Float.NaN)
                .putFloat("last_lon", Float.NaN)
                .putString("route", "[]")
                .apply()
            lastLocation = null
        }
        startForeground(NOTIFICATION_ID, notification())
        registerTrackers()
    }

    private fun registerTrackers() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 3f, this, Looper.getMainLooper())
            } catch (_: Exception) {
                // GPS may be disabled; step tracking can continue.
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER || !prefs.getBoolean("active", false)) return
        val total = event.values.firstOrNull() ?: return
        val previous = prefs.getFloat("last_counter", -1f)
        if (previous < 0f || total < previous) {
            prefs.edit().putFloat("last_counter", total).apply()
            return
        }
        val delta = (total - previous).toLong().coerceAtLeast(0L)
        if (delta > 0) prefs.edit().putLong("steps", prefs.getLong("steps", 0L) + delta).putFloat("last_counter", total).apply()
        else prefs.edit().putFloat("last_counter", total).apply()
        updateNotificationThrottled()
    }

    override fun onLocationChanged(location: Location) {
        if (!prefs.getBoolean("active", false) || !location.hasAccuracy() || location.accuracy > 35f) return
        val previous = lastLocation
        if (previous != null) {
            val segment = previous.distanceTo(location)
            if (segment in 2f..100f) {
                prefs.edit().putFloat("distance_m", prefs.getFloat("distance_m", 0f) + segment).apply()
            }
        }
        lastLocation = location
        val route = try { JSONArray(prefs.getString("route", "[]")) } catch (_: Exception) { JSONArray() }
        route.put(JSONObject().put("lat", location.latitude).put("lon", location.longitude).put("time", location.time))
        while (route.length() > 1500) route.remove(0)
        prefs.edit().putString("route", route.toString()).apply()
        updateNotificationThrottled()
    }

    private fun stopTracking() {
        try { sensors.unregisterListener(this) } catch (_: Exception) {}
        try { locations.removeUpdates(this) } catch (_: Exception) {}
        prefs.edit().putBoolean("active", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CalorieFit walking tracker")
            .setContentText("${prefs.getLong("steps", 0L)} steps · ${(prefs.getFloat("distance_m", 0f) / 1000f * 100).roundToInt() / 100.0} km")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotificationThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt > 5000L) updateNotification()
    }

    private fun updateNotification() {
        lastNotificationAt = System.currentTimeMillis()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Walking tracker", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shown while CalorieFit records a walking session"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
