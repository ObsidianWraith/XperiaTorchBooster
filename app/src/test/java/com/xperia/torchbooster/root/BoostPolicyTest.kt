package com.xperia.torchbooster.root

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoostPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val primary get() = File(temp.root, "torch0")
    private val secondary get() = File(temp.root, "torch3")
    private val latch get() = File(temp.root, "switch")
    private fun reset(current: Int) {
        primary.writeText("$current\n")
        secondary.writeText("0\n")
        latch.writeText("0\n")
    }
    private fun run(target: Int, trigger: Trigger, dual: Boolean = true): Int {
        val cmd = TorchCommands.boost(primary.path, secondary.path.takeIf { dual }, latch.path, target, trigger)
        return ProcessBuilder("sh", "-c", cmd).start().waitFor()
    }
    private fun values() = listOf(primary, secondary, latch).map { it.readText().trim().toInt() }

    @Test fun defaultsAndPerChannelBounds() {
        assertEquals(100, BoostPolicy.DEFAULT)
        assertEquals(25, BoostPolicy.clamp(-1, false, 500))
        assertEquals(200, BoostPolicy.clamp(500, false, 500))
        assertEquals(300, BoostPolicy.clamp(500, true, 500))
        assertEquals(95, BoostPolicy.clamp(150, true, 95))
    }
    @Test fun callbackCanLatchFromAllZero() {
        reset(0)
        assertEquals(0, run(100, Trigger.FRAMEWORK_ON))
        assertEquals(listOf(100,100,1), values())
    }
    @Test fun zeroVideoAndLiveSkipWithSuccess() {
        reset(0)
        for (trigger in listOf(Trigger.VIDEO_BELOW_TARGET, Trigger.LIVE_CHANGE)) {
            assertEquals(0, run(200, trigger))
            assertEquals(listOf(0,0,0), values())
        }
    }
    @Test fun videoAcceptsPositiveReadingsAboveOldDimRange() {
        reset(140)
        assertTrue(BoostPolicy.candidate(140, 200))
        assertEquals(0, run(200, Trigger.VIDEO_BELOW_TARGET))
        assertEquals(listOf(200,200,1), values())
    }
    @Test fun equalOrHigherVideoReadbackSkipsLatch() {
        for (current in listOf(100, 180)) {
            reset(current)
            assertFalse(BoostPolicy.candidate(current, 100))
            assertEquals(0, run(100, Trigger.VIDEO_BELOW_TARGET))
            assertEquals(listOf(current,0,0), values())
        }
    }
    @Test fun sliderCanLowerAndLatchSingleChannel() {
        reset(250)
        assertEquals(0, run(45, Trigger.LIVE_CHANGE, dual = false))
        assertEquals(listOf(45,0,1), values())
    }
    @Test fun failedSecondaryPreventsLatch() {
        reset(10)
        secondary.delete()
        secondary.mkdir()
        assertEquals(42, run(100, Trigger.FRAMEWORK_ON))
        assertEquals("100", primary.readText().trim())
        assertEquals("0", latch.readText().trim())
    }
    @Test fun rootSessionSurvivesSkippedJob() {
        reset(0)
        val cmd = TorchCommands.boost(primary.path, null, latch.path, 100, Trigger.LIVE_CHANGE)
        val proc = ProcessBuilder("sh", "-c", "$cmd && echo READY").start()
        assertEquals("READY", proc.inputStream.bufferedReader().readText().trim())
        assertEquals(0, proc.waitFor())
    }
}
