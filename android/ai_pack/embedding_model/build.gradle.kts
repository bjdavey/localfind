plugins {
    id("com.android.ai-pack")
}

aiPack {
    packName.set("embedding_model")
    dynamicDelivery {
        // install-time delivers the targeted .litertlm model together with the app, so it is present at the
        // first (offline) launch. Play exposes install-time pack files only through AssetManager, so the app
        // copies the model into context.filesDir once (SocDetector.stageAiPackModel).
        deliveryType.set("install-time")
    }
}

