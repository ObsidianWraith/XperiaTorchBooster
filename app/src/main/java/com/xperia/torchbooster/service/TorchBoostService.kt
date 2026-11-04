package com.xperia.torchbooster.service

import android.app.*
import android.content.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xperia.torchbooster.root.Trigger
import com.xperia.torchbooster.R
import com.xperia.torchbooster.data.PrefsManager
import com.xperia.torchbooster.root.BoostPolicy
import com.xperia.torchbooster.root.SysfsTorchController as Hardware
import com.xperia.torchbooster.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class TorchBoostService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: PrefsManager
    private lateinit var camera: CameraManager
    private lateinit var power: PowerManager
    private var cameraId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var systemOn = false
    @Volatile private var unavailable = false
    @Volatile private var paused = false
    @Volatile private var hot = false
    private var ready = false
    private var registered = false
    private var thermalRegistered = false
    private var testOwned = false
    private var qsJob: Job? = null
    private var videoJob: Job? = null
    private var testJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val thermal = PowerManager.OnThermalStatusChangedListener { status ->
        hot = status >= PowerManager.THERMAL_STATUS_MODERATE
        invalidate()
        if (hot) {
            finishTest()
            publish("Thermal pause • switch off the torch to end current output")
        } else reconcile()
    }
    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            if (systemOn == enabled) return
            systemOn = enabled
            invalidate()
            if (!enabled) {
                testOwned = false; testing.value = false; testJob?.cancel()
            }
            reconcile()
        }
        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            systemOn = false
            testOwned = false; testing.value = false; testJob?.cancel()
            invalidate()
            reconcile()
        }
    }
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(id: String) {
            if (id != cameraId) return
            unavailable = true
            invalidate(); reconcile()
        }
        override fun onCameraAvailable(id: String) {
            if (id != cameraId) return
            unavailable = false
            invalidate()
            reconcile()
        }
    }
    override fun onCreate() {
        super.onCreate()
        running.value = true
        prefs = PrefsManager(this)
        camera = getSystemService(CameraManager::class.java)
        power = getSystemService(PowerManager::class.java)
        startForeground(1001, notification("Checking root and device…"))
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        launchSafe {
            check(prefs.enabled && prefs.acknowledged) { "Enable and acknowledge hardware testing first" }
            val d = Hardware.diagnose()
            check(d.supported) { "Unsupported device/profile. No writes performed." }
            val ids = camera.cameraIdList.filter {
                val c = camera.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            cameraId = ids.firstOrNull { it == "0" } ?: ids.firstOrNull()
            check(cameraId != null) { "No rear flash camera found" }
            hot = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            power.addThermalStatusListener(mainExecutor, thermal)
            thermalRegistered = true
            camera.registerTorchCallback(torch, handler)
            camera.registerAvailabilityCallback(availability, handler)
            registered = true
            ready = true
            publish("Ready • camera $cameraId • target ${prefs.brightness}")
        }
    }
    private fun allowed() = ready && prefs.enabled && !paused && !hot
    private fun invalidate() {
        generation++
        qsJob?.cancel(); videoJob?.cancel()
    }
    private fun launchSafe(block: suspend () -> Unit): Job = scope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            paused = true
            invalidate()
            finishTest()
            publish("Paused: ${e.message}")
        }
    }
    private suspend fun boost(token: Long, trigger: Trigger) {
        if (token != generation || !allowed()) return
        val applied = Hardware.apply(prefs.brightness, prefs.high, prefs.dual, trigger) {
            token == generation && allowed() && when (trigger) {
                Trigger.FRAMEWORK_ON -> systemOn && prefs.qs
                Trigger.LIVE_CHANGE -> prefs.qs || prefs.video
                Trigger.VIDEO_BELOW_TARGET -> unavailable && prefs.video && !systemOn
            }
        }
        if (applied && token == generation) publish("Boost applied • target ${prefs.brightness}")
    }
    private fun reconcile(liveChange: Boolean = false) {
        if (!allowed()) return
        val token = generation
        if (systemOn && prefs.qs) {
            qsJob?.cancel()
            qsJob = launchSafe {
                if (!liveChange) delay(prefs.delay.toLong())
                boost(token, Trigger.FRAMEWORK_ON)
                // Second pass is at max(180ms, first pass + 40ms).
                delay((180 - prefs.delay).coerceAtLeast(40).toLong())
                boost(token, Trigger.FRAMEWORK_ON)
            }
        } else if ((unavailable && prefs.video) || (liveChange && (prefs.qs || prefs.video))) {
            videoJob?.cancel()
            videoJob = launchSafe {
                if (liveChange) boost(token, Trigger.LIVE_CHANGE)
                if (!prefs.video) return@launchSafe
                var stableSince = 0L
                var previous = -1
                while (currentCoroutineContext().isActive && token == generation && allowed() && unavailable && !systemOn) {
                    val d = Hardware.diagnose()
                    val current = d.nodes.firstOrNull { it.id == 0 }?.current ?: 0
                    val now = SystemClock.elapsedRealtime()
                    if (BoostPolicy.candidate(current, prefs.brightness)) {
                        if (current != previous || stableSince == 0L) stableSince = now
                        if (now - stableSince >= 750L) boost(token, Trigger.VIDEO_BELOW_TARGET)
                    } else stableSince = 0L
                    previous = current
                    delay(prefs.poll.toLong())
                }
            }
        }
    }
    override fun onSharedPreferenceChanged(store: SharedPreferences?, key: String?) {
        if (key == "last_status" || key == "boot") return
        invalidate()
        if (!prefs.enabled) { stopSelf(); return }
        reconcile(liveChange = key == "brightness" || key == "high" || key == "dual")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> {
                paused = !paused
                invalidate()
                if (paused) finishTest() else reconcile()
                publish(if (paused) "Paused" else "Resumed")
            }
            STOP -> { prefs.set("enabled", false); stopSelf() }
            TEST -> {
                if (ready && allowed() && !systemOn && !unavailable && !testOwned) {
                    try {
                        camera.setTorchMode(requireNotNull(cameraId), true)
                        testOwned = true; testing.value = true
                        testJob?.cancel()
                        testJob = launchSafe { delay(10_000); finishTest() }
                    } catch (e: Exception) { publish("Test unavailable: ${e.message}") }
                } else publish("Test requires ready service and an unused, unlit rear camera")
            }
            END_TEST -> finishTest()
        }
        return START_NOT_STICKY // Root auto-restart requires explicit boot opt-in or user action.
    }
    private fun finishTest() {
        testJob?.cancel()
        if (testOwned) {
            testOwned = false
            try { cameraId?.let { camera.setTorchMode(it, false) } }
            catch (e: Exception) { status.value = "Could not end test: ${e.message}; use system flashlight control" }
        }
        testing.value = false
    }
    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("boost", "Torch Booster", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, TorchBoostService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "boost").setSmallIcon(R.drawable.ic_torch)
            .setContentTitle("Xperia Torch Booster").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Resume" else "Pause", action(PAUSE, 1))
            .addAction(0, "Stop", action(STOP, 2)).build()
    }
    private fun publish(message: String) {
        status.value = message
        prefs.store.edit().putString("last_status", message).apply()
        getSystemService(NotificationManager::class.java).notify(1001, notification(message))
    }
    override fun onDestroy() {
        ready = false
        invalidate()
        finishTest()
        if (registered) {
            camera.unregisterTorchCallback(torch)
            camera.unregisterAvailabilityCallback(availability)
        }
        if (thermalRegistered) power.removeThermalStatusListener(thermal)
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
        running.value = false
        status.value = "Stopped"
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PAUSE = "com.xperia.torchbooster.PAUSE"
        const val STOP = "com.xperia.torchbooster.STOP"
        const val TEST = "com.xperia.torchbooster.TEST"
        const val END_TEST = "com.xperia.torchbooster.END_TEST"
        val running = MutableStateFlow(false)
        val testing = MutableStateFlow(false)
        val status = MutableStateFlow("Stopped")
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TorchBoostService::class.java))
        }
        fun send(context: Context, action: String) {
            if (running.value) context.startService(Intent(context, TorchBoostService::class.java).setAction(action))
        }
    }
}
