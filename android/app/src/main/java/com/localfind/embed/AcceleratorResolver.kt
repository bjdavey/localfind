package com.localfind.embed

import android.content.Context
import android.os.PowerManager
import com.localfind.core.settings.Capabilities
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.settings.loadOrder

/** What this device can run with [models]: NPU needs its runtime libraries and the NPU model, GPU/CPU the universal one. */
fun capabilitiesOf(context: Context, models: ModelFiles.Models): Capabilities = Capabilities(
    npu = models.npu != null && SocDetector.isNpuRuntimeAvailable(context),
    cpuGpu = models.universal != null
)

/**
 * The accelerators to try for this setting, in order, feeding the live device state (screen interactivity,
 * Battery Saver, for Smart mode) into the pure plan [HardwareAccelerator.loadOrder].
 */
fun HardwareAccelerator.loadOrder(context: Context, caps: Capabilities): List<HardwareAccelerator> {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return loadOrder(
        caps,
        screenOn = { powerManager?.isInteractive == true },
        powerSaveMode = { powerManager?.isPowerSaveMode == true }
    )
}
