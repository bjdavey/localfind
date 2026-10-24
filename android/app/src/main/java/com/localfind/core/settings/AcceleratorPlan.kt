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
 * rule), followed by the remaining available accelerators (NPU > GPU > CPU) as fallbacks when a load fails.
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
    return (listOf(primary) + available).distinct().filter { it in available }
}
