package com.localfind.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unit tests for the pure accelerator resolution policy [HardwareAccelerator.resolve]. */
class HardwareAcceleratorTest {

    private fun HardwareAccelerator.resolveWith(
        npu: Boolean = false,
        screenOn: Boolean = true,
        powerSave: Boolean = false
    ) = resolve({ npu }, { screenOn }, { powerSave })

    @Test
    fun npuIsKeptWhenRuntimeAvailable_regardlessOfScreenAndBattery() {
        assertEquals(HardwareAccelerator.NPU, HardwareAccelerator.NPU.resolveWith(npu = true, screenOn = true, powerSave = true))
        assertEquals(HardwareAccelerator.NPU, HardwareAccelerator.NPU.resolveWith(npu = true, screenOn = false))
    }

    @Test
    fun npuFallsBackToSmartPolicyWhenUnavailable() {
        // Screen on -> CPU; screen off and no battery saver -> GPU
        assertEquals(HardwareAccelerator.CPU, HardwareAccelerator.NPU.resolveWith(npu = false, screenOn = true))
        assertEquals(HardwareAccelerator.GPU, HardwareAccelerator.NPU.resolveWith(npu = false, screenOn = false))
    }

    @Test
    fun explicitGpuAndCpuAreNeverChanged() {
        assertEquals(HardwareAccelerator.GPU, HardwareAccelerator.GPU.resolveWith(screenOn = true, powerSave = true))
        assertEquals(HardwareAccelerator.CPU, HardwareAccelerator.CPU.resolveWith(screenOn = false))
    }

    @Test
    fun smartUsesGpuOnlyWhenScreenOffAndNoBatterySaver() {
        assertEquals(HardwareAccelerator.GPU, HardwareAccelerator.SMART.resolveWith(screenOn = false, powerSave = false))
        assertEquals(HardwareAccelerator.CPU, HardwareAccelerator.SMART.resolveWith(screenOn = false, powerSave = true))
        assertEquals(HardwareAccelerator.CPU, HardwareAccelerator.SMART.resolveWith(screenOn = true, powerSave = false))
    }

    @Test
    fun npuOnlyModelForcesNpuForEveryChoice() {
        // Only an NPU-compiled model is installed (e.g. the Play AI Pack on a Snapdragon 8 Gen 2/3):
        // Smart/GPU/CPU cannot run it, so all of them resolve to NPU.
        HardwareAccelerator.values().forEach { choice ->
            assertEquals(
                HardwareAccelerator.NPU,
                choice.resolve({ true }, { true }, { false }, cpuGpuModelAvailable = { false })
            )
        }
    }

    @Test
    fun npuOnlyModelWithoutNpuRuntimeKeepsTheChoice() {
        // Nothing can run the model then; keep the normal policy so the load fails with its own error.
        assertEquals(
            HardwareAccelerator.CPU,
            HardwareAccelerator.CPU.resolve({ false }, { true }, { false }, cpuGpuModelAvailable = { false })
        )
    }

    @Test
    fun deviceStateIsOnlyQueriedWhenTheChoiceNeedsIt() {
        var queried = false
        val probe = { queried = true; false }
        HardwareAccelerator.GPU.resolve(probe, probe, probe)
        HardwareAccelerator.CPU.resolve(probe, probe, probe)
        assertFalse("explicit choices must not query device state", queried)
    }
}
