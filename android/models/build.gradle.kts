// Install-time asset pack with the ONNX models: delivered with the app by Play,
// read through the normal AssetManager. Files come from scripts/fetch_models.sh
// or ml/export_android.py.
plugins {
    alias(libs.plugins.android.assetpack)
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
