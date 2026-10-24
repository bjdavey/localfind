package com.localfind.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure load plan [HardwareAccelerator.loadOrder]. */
class AcceleratorPlanTest {

    private val npu = HardwareAccelerator.NPU
    private val gpu = HardwareAccelerator.GPU
    private val cpu = HardwareAccelerator.CPU

    private fun HardwareAccelerator.order(caps: Capabilities, screenOn: Boolean = true, powerSave: Boolean = false) =
        loadOrder(caps, { screenOn }, { powerSave })

    @Test
    fun bothModels_gpuIsNeverAFallbackUnlessChosenOrSmartPicksIt() {
        val caps = Capabilities(npu = true, cpuGpu = true)
        // Failed NPU degrades to Smart: screen on -> CPU only, screen off -> GPU then CPU
        assertEquals(listOf(npu, cpu), npu.order(caps, screenOn = true))
        assertEquals(listOf(npu, gpu, cpu), npu.order(caps, screenOn = false))
        // Battery Saver keeps Smart off the GPU even with the screen off
        assertEquals(listOf(npu, cpu), npu.order(caps, screenOn = false, powerSave = true))
        // Explicit choices never fall back to the NPU or (for CPU) the GPU
        assertEquals(listOf(gpu, cpu), gpu.order(caps))
        assertEquals(listOf(cpu), cpu.order(caps))
    }

    @Test
    fun smartKeepsItsScreenRule() {
        val caps = Capabilities(npu = true, cpuGpu = true)
        assertEquals(listOf(gpu, cpu), HardwareAccelerator.SMART.order(caps, screenOn = false))
        assertEquals(listOf(cpu), HardwareAccelerator.SMART.order(caps, screenOn = true))
    }

    @Test
    fun npuOnlyModel_everyChoiceLoadsNpuWithoutImpossibleFallbacks() {
        // Play AI Pack on an NPU device group, or deploy-bundle staging only the chipset model
        val caps = Capabilities(npu = true, cpuGpu = false)
        HardwareAccelerator.values().forEach { choice ->
            assertEquals(listOf(npu), choice.order(caps))
        }
    }

    @Test
    fun universalModelOnly_npuChoiceFallsBackToSmart() {
        // Emulators and SoCs without an NPU model: NPU is never attempted
        val caps = Capabilities(npu = false, cpuGpu = true)
        assertEquals(listOf(cpu), npu.order(caps, screenOn = true))
        assertEquals(listOf(gpu, cpu), npu.order(caps, screenOn = false))
    }

    @Test
    fun noRunnableModel_isEmpty() {
        val caps = Capabilities(npu = false, cpuGpu = false)
        HardwareAccelerator.values().forEach { choice ->
            assertEquals(emptyList<HardwareAccelerator>(), choice.order(caps))
        }
    }
}
