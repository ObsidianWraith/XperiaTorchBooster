package com.xperia.torchbooster.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xperia.torchbooster.data.PrefsManager
import com.xperia.torchbooster.root.SysfsTorchController
import com.xperia.torchbooster.service.TorchBoostService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
    override fun onStop() {
        TorchBoostService.send(this, TorchBoostService.END_TEST)
        super.onStop()
    }
    @Composable private fun Screen() {
        val prefs = remember { PrefsManager(this) }
        var revision by remember { mutableIntStateOf(0) }
        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
            prefs.store.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.store.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val running by TorchBoostService.running.collectAsState()
        val testing by TorchBoostService.testing.collectAsState()
        val status by TorchBoostService.status.collectAsState()
        val scope = rememberCoroutineScope()
        var diagnostic by remember { mutableStateOf("Tap Refresh to request root and inspect the hardware. No writes are performed.") }
        var busy by remember { mutableStateOf(false) }
        var supported by remember { mutableStateOf(false) }
        var ackDialog by remember { mutableStateOf(false) }
        var highDialog by remember { mutableStateOf(false) }
        var videoDialog by remember { mutableStateOf(false) }
        var advanced by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var brightness by remember { mutableFloatStateOf(prefs.brightness.toFloat()) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) message = "Notifications denied. Android may hide the service's Pause/Stop controls; the app controls remain available."
        }
        fun refresh() {
            scope.launch {
                busy = true
                try {
                    val d = SysfsTorchController.diagnose()
                    diagnostic = d.summary; supported = d.supported
                } catch (e: Exception) { diagnostic = "Diagnostics failed: ${e.message}"; supported = false }
                finally { busy = false }
            }
        }
        fun enable() {
            prefs.set("ack", true)
            prefs.set("enabled", true)
            try {
                TorchBoostService.start(this)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (e: RuntimeException) { prefs.set("enabled", false); message = "Could not start: ${e.message}" }
        }
        LaunchedEffect(brightness) {
            delay(120)
            if (prefs.brightness != brightness.roundToInt()) prefs.set("brightness", brightness.roundToInt())
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Xperia Torch Booster", style = MaterialTheme.typography.headlineMedium)
            Text("Revision 002 • experimental hardware utility", style = MaterialTheme.typography.labelLarge)
            Text("Start low. Brightness values are driver settings, not measured lumens or a certified safe current.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Panel("Device diagnostics") {
                Text(diagnostic)
                Text("Readouts may be cached driver values; they do not prove physical LED current.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text(if (busy) "Checking…" else "Refresh") }
                    TextButton(onClick = {
                        val report = "Xperia Torch Booster rev002\n${Build.MODEL} / ${Build.DEVICE} / Android ${Build.VERSION.RELEASE}\n$diagnostic\n$status"
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share diagnostics"))
                    }) { Text("Share") }
                }
            }
            Panel("Service") {
                Text(status)
                if (!running) Text(prefs.store.getString("last_status", "") ?: "", style = MaterialTheme.typography.bodySmall)
                Toggle("Enable boost service", running, enabled = supported || running) { on ->
                    if (on) { if (prefs.acknowledged) enable() else ackDialog = true }
                    else { prefs.set("enabled", false); stopService(Intent(this@MainActivity, TorchBoostService::class.java)) }
                }
                if (!supported) Text("Run diagnostics before enabling.", style = MaterialTheme.typography.bodySmall)
                Toggle("Start after reboot and unlock", prefs.flag("boot")) { prefs.set("boot", it) }
                if (running) OutlinedButton(onClick = { TorchBoostService.send(this@MainActivity, TorchBoostService.PAUSE) }) { Text("Pause / resume") }
            }
            Panel("Brightness • ${brightness.roundToInt()}") {
                Slider(value = brightness.coerceIn(25f, if (prefs.high) 300f else 200f), onValueChange = { brightness = it },
                    valueRange = 25f..if (prefs.high) 300f else 200f, steps = if (prefs.high) 274 else 174)
                Text("Default 100 • normal range 25–200")
                Toggle("Set both channels (0 and 3)", prefs.dual) { prefs.set("dual", it) }
                Text("With both channels enabled, the app sets channels 0 and 3 before latching the brightness.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { TorchBoostService.send(this@MainActivity, if (testing) TorchBoostService.END_TEST else TorchBoostService.TEST) }, enabled = running) {
                    Text(if (testing) "End test now" else "Test brightness • 10 seconds")
                }
                Text("Test stops when you leave this screen. Enable standard flashlight boosting for a boosted preview.", style = MaterialTheme.typography.bodySmall)
            }
            Panel("Detection") {
                Toggle("Boost standard flashlight", prefs.qs) { prefs.set("qs", it) }
                Toggle("Experimental camera / video boost", prefs.video) { if (it) videoDialog = true else prefs.set("video", false) }
                Text("Video detection is a heuristic. It may miss Sony camera modes or mistake a sustained photo-assist light for video torch.", style = MaterialTheme.typography.bodySmall)
            }
            Panel("Timing and safeguards") {
                Text("Boost lasts until the torch turns off. Pause and thermal status suspend further writes. Turn the flashlight off to end the current output.")
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Show advanced settings") }
                if (advanced) {
                    Text("First override delay: ${prefs.delay} ms")
                    Slider(prefs.delay.toFloat(), { prefs.set("delay", it.roundToInt()) }, valueRange = 20f..200f)
                    Text("Video polling interval: ${prefs.poll} ms")
                    Slider(prefs.poll.toFloat(), { prefs.set("poll", it.roundToInt()) }, valueRange = 250f..1000f)
                    Toggle("Unlock experimental range to 300", prefs.high) {
                        if (it) highDialog = true else {
                            brightness = brightness.coerceAtMost(200f)
                            prefs.store.edit().putBoolean("high", false).putInt("brightness", brightness.roundToInt()).apply()
                        }
                    }
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            Text("No network permission. No camera recording. No sysfs permission changes. Disable boost if you see flicker, camera errors, or unexpected warmth.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
        if (ackDialog) AlertDialog(onDismissRequest = { ackDialog = false }, title = { Text("Enable experimental hardware adjustment?") },
            text = { Text("The node layout matches, but this firmware and its thermal limits have not been tested here. Use a brief first test. The normal and extended ranges have no verified continuous thermal rating on this phone.") },
            confirmButton = { TextButton(onClick = { ackDialog = false; enable() }) { Text("Enable") } },
            dismissButton = { TextButton(onClick = { ackDialog = false }) { Text("Cancel") } })
        if (highDialog) AlertDialog(onDismissRequest = { highDialog = false }, title = { Text("Higher current can damage the LED") },
            text = { Text("201–300 enables higher sustained current. Local heating can damage the LED or diffuser before Android reports overheating. Turn the flashlight off to end the boost.") },
            confirmButton = { TextButton(onClick = { highDialog = false; prefs.set("high", true) }) { Text("Unlock") } },
            dismissButton = { TextButton(onClick = { highDialog = false }) { Text("Cancel") } })
        if (videoDialog) AlertDialog(onDismissRequest = { videoDialog = false }, title = { Text("Experimental video interception") },
            text = { Text("Another app's capture requests are not visible. This mode watches for a stable torch reading below the target for at least 750 ms while the rear camera is unavailable. It cannot reliably distinguish video from focus assist. Avoid still-photo flash testing with this enabled.") },
            confirmButton = { TextButton(onClick = { videoDialog = false; prefs.set("video", true) }) { Text("Enable experimental mode") } },
            dismissButton = { TextButton(onClick = { videoDialog = false }) { Text("Cancel") } })
    }
}
@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun Toggle(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp, top = 12.dp))
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}
