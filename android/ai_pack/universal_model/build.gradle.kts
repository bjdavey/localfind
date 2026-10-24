plugins {
    id("com.android.ai-pack")
}

aiPack {
    packName.set("universal_model")
    dynamicDelivery {
        // on-demand: NOT installed with the app. NPU devices only get their NPU-compiled model (install-time
        // :ai_pack:embedding_model), which cannot run on GPU/CPU. When the NPU fails on such a device, the app asks
        // Play to download this universal CPU/GPU model (RecoveryModel); Play downloads it, the app makes no
        // network calls. No device targeting: every device gets the same universal model.
        deliveryType.set("on-demand")
    }
}
