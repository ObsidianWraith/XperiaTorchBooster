package com.xperia.torchbooster.root

object BoostPolicy {
    const val MIN = 25
    const val DEFAULT = 100
    const val NORMAL_MAX = 200
    const val EXTENDED_MAX = 300

    fun limit(high: Boolean) = if (high) EXTENDED_MAX else NORMAL_MAX
    fun clamp(request: Int, high: Boolean, driverMax: Int): Int {
        require(driverMax >= MIN) { "Unsupported driver maximum: $driverMax" }
        return request.coerceIn(MIN, minOf(limit(high), driverMax))
    }
    fun candidate(current: Int, target: Int) = current > 0 && current < target
}

enum class Trigger { FRAMEWORK_ON, LIVE_CHANGE, VIDEO_BELOW_TARGET }

object TorchCommands {
    fun boost(primary: String, secondary: String?, latch: String, target: Int, trigger: Trigger): String {
        require(target in BoostPolicy.MIN..BoostPolicy.EXTENDED_MAX)
        // Paths come only from the fixed device profile. A subshell keeps exit local to this job.
        return buildString {
            appendLine("(")
            if (trigger != Trigger.FRAMEWORK_ON) {
                appendLine("current=\$(cat '$primary') || exit 31")
                appendLine("case \"\$current\" in ''|*[!0-9]*) echo 'Invalid torch_0 readback' >&2; exit 32;; esac")
                appendLine("[ \"\$current\" -gt 0 ] || exit 0")
                if (trigger == Trigger.VIDEO_BELOW_TARGET) appendLine("[ \"\$current\" -lt $target ] || exit 0")
            }
            appendLine("echo $target > '$primary' || exit 41")
            if (secondary != null) appendLine("echo $target > '$secondary' || exit 42")
            appendLine("echo 1 > '$latch' || exit 43")
            appendLine("echo APPLIED")
            appendLine("exit 0")
            append(")")
        }
    }
}
