package com.localfind.core.settings

/**
 * What this device can actually run, decided once from the installed models (see `ModelFiles.locate`):
 * - [npu]: the NPU runtime libraries are installed AND the chipset-specific NPU model is present.
 * - [cpuGpu]: the universal model is present. A chipset NPU model cannot serve GPU/CPU: its text and vision
 *   encoders are compiled into an NPU-only `DISPATCH_OP` (it loads, then the first embedding fails).
 */
data class Capabilities(val npu: Boolean, val cpuGpu: Boolean)

/**
 * Pure load plan: the accelerators to try, in order, for this setting on a device with [caps].
 *
 * The first entry is the policy's choice ([resolve], including Smart's screen/battery rule and the NPU-only
 * rule). Fallbacks when a load fails never pick the GPU on their own: a failed NPU falls back to Smart's pick
 * (GPU only when Smart would use it: screen off, no Battery Saver) and CPU is the last resort. The GPU is only
 * tried first when the user chose it (or Smart picks it).
 * Accelerators the device cannot run are never included, so no load is attempted that is bound to fail.
 * Empty when no installed model can run at all.
 */
fun HardwareAccelerator.loadOrder(
    caps: Capabilities,
    screenOn: () -> Boolean,
    powerSaveMode: () -> Boolean
): List<HardwareAccelerator> {
    val available = buildList {
        if (caps.npu) add(HardwareAccelerator.NPU)
        if (caps.cpuGpu) {
            add(HardwareAccelerator.GPU)
            add(HardwareAccelerator.CPU)
        }
    }
    val primary = resolve({ caps.npu }, screenOn, powerSaveMode, { caps.cpuGpu })
    // Only a failed NPU degrades to Smart; GPU/CPU/Smart choices keep to themselves plus the CPU safety net
    val smartPick = if (this == HardwareAccelerator.NPU) {
        HardwareAccelerator.SMART.resolve({ false }, screenOn, powerSaveMode)
    } else {
        primary
    }
    return listOf(primary, smartPick, HardwareAccelerator.CPU).distinct().filter { it in available }
}
