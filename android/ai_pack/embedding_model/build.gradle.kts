plugins {
    id("com.android.ai-pack")
}

aiPack {
    packName.set("embedding_model")
    dynamicDelivery {
        // fast-follow downloads the targeted .litertlm model automatically right after install
        // and extracts it directly to disk (AiPackManager.getPackLocation), avoiding duplicate
        // 485 MB storage copies inside context.filesDir.
        deliveryType.set("fast-follow")
    }
}

