package com.localfind.embed

import android.content.Context
import android.os.PowerManager
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.settings.resolve

/**
 * Resolves the effective concrete accelerator for this device by feeding the live device state
 * (NPU runtime availability, screen interactivity, Battery Saver) into the pure policy
 * [HardwareAccelerator.resolve].
 */
fun HardwareAccelerator.resolveEffective(context: Context): HardwareAccelerator {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return resolve(
        npuAvailable = { SocDetector.isNpuRuntimeAvailable(context) },
        screenOn = { powerManager?.isInteractive == true },
        powerSaveMode = { powerManager?.isPowerSaveMode == true }
    )
}
