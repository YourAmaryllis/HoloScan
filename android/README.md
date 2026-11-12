# HoloScan for Android

Camera card identification, fully on-device: the model, the 26k-card index and
the card info are bundled in the APK. No account needed; the network is used
only by *Refresh prices*.

## Install on a phone

1. On the phone: Settings → About phone → tap *Build number* 7× → Developer
   options → enable *USB debugging*.
2. Plug it in, then from `HoloScan/android`:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Or copy the APK to the phone and open it (allow "install unknown apps").

## Use

- Hold a card **upright** inside the white frame, filling it edge to edge. Plain
  background, little glare. The small thumbnail at the top is exactly what the
  model saw (224×308).
- At 50% or more on two frames in a row the card **locks**: it stays on screen
  until the score drops below 40% (card removed) or another card clearly beats it.
  50–60% is shown in amber (check the card); ≥ 60% is green.
- **✓** adds the card. **✕** pauses the camera and lists the detected card plus
  the other candidates. Tap the right printing, or Cancel to return to auto-detect.
- **Auto-add** (Settings: off, 1, 2, 3 or 5 s; default 2 s) adds a green match
  after it's held that long. A green "✓ Card added" banner and frame confirm it.
- **Collection** lists saved cards with +/- counts, prices and an estimated total.
  *Refresh prices* updates them (5 per hour, 5 min apart).
- **Settings:** model (Fast int8 / Full fp32), auto-add delay, button side, and
  Export / Import / Reset of the collection (zip of CSV files).
- Same-art reprints (Base Set vs Base Set 2, stamped promos, ...) look identical
  to the model. Check the set and number and use ✕ to pick the exact one.

## Build

```sh
../scripts/fetch_models.sh          # embedder.onnx + embedder.int8.onnx -> models/src/main/assets/
./gradlew :app:testDebugUnitTest    # lock logic, backup CSV, price limits (+ parity if fixtures exist)
./gradlew :app:assembleDebug        # debug APK with the models included
```

Play release: `./gradlew :app:bundleRelease` builds an app bundle with the models in the
install-time asset pack `:models` (about 43 MB base download). It's signed with the
upload key given by the Gradle properties `holoscanUploadStoreFile` and
`holoscanUploadStorePassword` (alias `upload`, or `holoscanUploadKeyAlias`);
without them it's debug-signed.

To rebuild the assets from the pipeline instead:
`cd ../ml && uv run python export_android.py --tag dinov2-s14-224x308-ft`. That also
writes the parity fixtures (card images, kept local) that check the Kotlin
preprocessing + ONNX against Python.

## How it works

`CardAnalyzer` takes each camera frame (4:3, same as the preview), rotates it
upright, and crops the region under the on-screen frame. It resizes that
crop to 224×308 with the same bilinear filter as PIL (`Preprocess.kt`), runs
`embedder.onnx` (ONNX Runtime, XNNPACK), and dot-products the 384-number
embedding against every card in `index.f16`.
