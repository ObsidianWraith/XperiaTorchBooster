package com.xperia.torchbooster.root

import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Node(val id: Int, val current: Int, val maximum: Int) {
    val path get() = "/sys/class/leds/led:torch_$id/brightness"
}
data class Diagnostic(val nodes: List<Node>, val switchValue: Int, val supported: Boolean) {
    val summary get() = "Root granted • ${Build.MODEL} / ${Build.DEVICE}\n" +
        nodes.joinToString("\n") { "torch_${it.id}: ${it.current} / ${it.maximum}" } +
        "\nswitch_0: $switchValue\n" + if (supported) "Expected profile detected" else "Unsupported profile: writes blocked"
}
object SysfsTorchController {
    private const val SWITCH = "/sys/class/leds/led:switch_0/brightness"
    private val mutex = Mutex()
    private fun run(command: String): List<String> {
        check(Shell.getShell().isRoot) { "Root denied or unavailable. Grant root and retry." }
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val result = Shell.cmd(command).to(output, errors).exec()
        check(result.isSuccess) { "Root I/O failed (${result.code}): ${errors.joinToString(" ").take(240)}" }
        return output
    }
    private fun readUnlocked(): Diagnostic {
        val lines = run("( for n in 0 1 2 3; do p=/sys/class/leds/led:torch_\$n; " +
            "if [ -e \"\$p/brightness\" ]; then " +
            "b=\$(cat \"\$p/brightness\") || exit 31; m=\$(cat \"\$p/max_brightness\") || exit 32; " +
            "echo N:\$n:\$b:\$m; fi; done; s=\$(cat '$SWITCH') || exit 33; echo S:\$s; exit 0 )")
        val nodes = lines.filter { it.startsWith("N:") }.map {
            val v = it.split(':'); Node(v[1].toInt(), v[2].toInt(), v[3].toInt())
        }
        val sw = lines.first { it.startsWith("S:") }.substringAfter(':').toInt()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.uppercase()
        val supported = (device.contains("pdx215") || model.startsWith("XQ-BC")) &&
            nodes.any { it.id == 0 && it.maximum >= BoostPolicy.MIN }
        return Diagnostic(nodes, sw, supported)
    }
    suspend fun diagnose() = withContext(Dispatchers.IO) { mutex.withLock { readUnlocked() } }
    suspend fun apply(target: Int, high: Boolean, dual: Boolean, trigger: Trigger, valid: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!valid()) return@withLock false
                val profile = readUnlocked()
                check(profile.supported) { "Unsupported device or primary torch node" }
                val primary = profile.nodes.first { it.id == 0 }
                val secondary = if (dual) profile.nodes.firstOrNull { it.id == 3 }
                    ?: error("Dual channel requires torch_3") else null
                val maximum = minOf(primary.maximum, secondary?.maximum ?: primary.maximum)
                val value = BoostPolicy.clamp(target, high, maximum)
                val command = TorchCommands.boost(primary.path, secondary?.path, SWITCH, value, trigger)
                currentCoroutineContext().ensureActive()
                if (!valid()) return@withLock false
                "APPLIED" in run(command)
            }
        }
}
