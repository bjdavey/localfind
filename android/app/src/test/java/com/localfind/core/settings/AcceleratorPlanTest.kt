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
    fun bothModels_choiceFirstThenRemainingAsFallbacks() {
        val caps = Capabilities(npu = true, cpuGpu = true)
        assertEquals(listOf(npu, gpu, cpu), npu.order(caps))
        assertEquals(listOf(gpu, npu, cpu), gpu.order(caps))
        assertEquals(listOf(cpu, npu, gpu), cpu.order(caps))
    }

    @Test
    fun smartKeepsItsScreenRule() {
        val caps = Capabilities(npu = true, cpuGpu = true)
        assertEquals(listOf(gpu, npu, cpu), HardwareAccelerator.SMART.order(caps, screenOn = false))
        assertEquals(listOf(cpu, npu, gpu), HardwareAccelerator.SMART.order(caps, screenOn = true))
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
        assertEquals(listOf(cpu, gpu), npu.order(caps, screenOn = true))
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
