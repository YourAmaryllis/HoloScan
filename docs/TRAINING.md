# HoloScan: data, training and models

How the card-recognition model was built: where the data comes from, how it's
prepared, how the model is trained, every hyperparameter, and how the phone
models (full vs int8) differ. Code is in `ml/`.

## 1. How recognition works

The model never "classifies" a card. It turns an image into an **embedding**:
384 numbers, normalised to length 1. Every known card has its embedding stored
in an index. To identify a photo, the phone embeds the crop and returns the
card whose embedding is closest (highest dot product = cosine similarity).

- Adding new sets only means embedding their images and appending them to the
  index. No retraining is needed, unless the new cards look very different.
- The "match %" in the app is that cosine similarity × 100.
- Android and iPhone run the same network on the same input and search the
  same index, so they give the same answers (see §7).

## 2. Data collection

| What | Source | Where it's stored |
|---|---|---|
| Card + set metadata (JSON) | [PokemonTCG/pokemon-tcg-data](https://github.com/PokemonTCG/pokemon-tcg-data) GitHub repo (the data behind pokemontcg.io), master snapshot of 2026-11-10 | NAS `~/nas/arthur/pokemon-tcg/data`, local copy `cache/data` |
| Card images, **large** (hi-res PNG) | `card.images.large` URLs (images.pokemontcg.io / images.scrydex.com) | NAS `~/nas/arthur/pokemon-tcg/images/<set>/<card_id>.png`, untouched |
| Training/index working copies | Same large images, fetched from the CDN | `cache/train/<card_id>.jpg` (336×462) |

- Extra cards (`extra_sets.py`): promos, McDonald's, Prize Pack / deck exclusives,
  World Championship decks, jumbo cards and other products listed on TCGplayer but
  missing from that repo, via the free [TCGCSV](https://tcgcsv.com) mirror and
  TCGplayer product images. They're added to the index without retraining. The
  shipped index has **26,675** cards.
- Training used the first 20,776 English cards in 174 sets. 20,723 have images. The 53 without one
  (mostly McDonald's promos) aren't in the index.
- `download_images.py` can be re-run safely. A file is skipped when its size
  matches the server's `Content-Length`. Downloads are written to `.part` and
  renamed only after the length checks out. Failures go to `images/failed.json`.
  It uses 8 parallel downloads and pauses if local disk drops below 5 GB, since
  the NAS mount caches writes locally.
- `build_cache.py` makes the working copies. Transparent card corners are
  filled with grey (128,128,128). Each image is resized to 336×462 (Lanczos)
  and saved as JPEG quality 95. That's 1.5× the model input, small enough to
  read fast during training, and 1.7 GB in total. The pipeline reads these
  instead of the NAS because NAS reads were too slow while uploads were pending.

### Reprints ("art groups")

Many cards are reprinted with identical artwork, differing only in set symbol,
number or holo pattern. A camera image often can't tell them apart, so training
treats them as one identity. `art_groups.py`:

1. Embeds every card with the *pretrained* model.
2. For cards with the **same name**, links pairs whose cosine similarity is
   ≥ **0.95**.
3. Merges linked cards into groups (union-find).

Result: 1,568 cards fall into 717 multi-card groups. That leaves **19,872
classes** for 20,723 cards. The index still holds every printing separately,
and the app lists the alternatives so the user picks the exact one.

## 3. Model

| | |
|---|---|
| Backbone | DINOv2 ViT-S/14 (`timm: vit_small_patch14_dinov2.lvd142m`), ~22M parameters, self-supervised pretrained by Meta on 142M images |
| Input | RGB 224 wide × 308 high (card aspect 63:88), fixed size |
| Output | CLS token, 384 numbers, L2-normalised |
| Index | 20,723 × 384, stored as float16 (15.9 MB), shared by Android and iPhone |

Why DINOv2-S: its features are strong for "is this the same image" retrieval,
it's small enough to run on a phone, and its fixed input size exports cleanly to
ONNX and Core ML. The image size is fixed (not dynamic) because timm's dynamic
position-embedding resize uses an antialiased bicubic op that ONNX can't export.

**Preprocessing contract** (must be identical everywhere): crop the card, fill
transparent pixels grey, stretch to 224×308 with bilinear resampling (PIL
semantics), and scale pixels to [0,1]. ImageNet mean/std normalisation and the
final L2 normalisation are *inside* the exported model. Android reimplements
PIL's bilinear resize in Kotlin (`Preprocess.kt`) so the pixels match exactly.

## 4. Training method

The pretrained model already finds the right card for 76% of the simulated
photos (79% if same-art reprints count). Fine-tuning teaches it to ignore
what a phone camera adds: glare, colour cast, blur, perspective, JPEG
artefacts.

**Method:** metric learning with an **ArcFace** loss, trained on synthetic
phone captures (`finetune.py`):

- Each step takes a clean card image, applies a random "phone-like"
  distortion (§5), and embeds it.
- An ArcFace head holds one weight vector per class (19,872). The loss pulls the
  distorted card's embedding toward its own class vector, and an angular margin
  forces it to be clearly closer to its own class than to any other.
- Each class vector **starts at the clean card's own embedding** (not random), so
  training begins from "the index", and the head learns quickly.
- The first half of the network is **frozen** (patch embedding, position
  embedding, CLS token, transformer blocks 1–6). It keeps its general visual
  features, and only blocks 7–12 + the final norm learn. That's ~half the
  weights, which limits overfitting with only one real image per card.
- Every epoch sees a **new** random distortion of every card (seeded by
  epoch and index, so runs are reproducible).
- After training, the head is thrown away. Only the backbone is kept
  (`embeddings/dinov2-s14-224x308-ft/backbone.pt`) and used to re-embed all
  clean cards into the final index.

### Hyperparameters

| Parameter | Value |
|---|---|
| Epochs | 8 |
| Batch size | 48 (431 steps/epoch, 3,448 steps total, last partial batch dropped) |
| Optimizer | AdamW, weight decay 0.05 |
| Learning rate, backbone | 2e-5 (peak) |
| Learning rate, ArcFace head | 1e-3 (peak, 50× backbone) |
| LR schedule | OneCycle: 5% warm-up, then cosine decay (PyTorch defaults: starts at peak/25, ends at peak/250,000) |
| ArcFace scale *s* | 30 |
| ArcFace margin *m* | 0.25 rad (~14°) |
| Frozen | patch embed, pos embed, CLS token, blocks 1–6 of 12 |
| Art-group threshold | cosine ≥ 0.95, same card name |
| Precision / device | fp32 on Apple M4 GPU (PyTorch MPS) |
| Data loading | 8 workers, shuffled, fresh distortion per epoch |
| Wall time | ~2 h 10 min (23:07 → 01:18) |

Loss per epoch (mean): 2.86 → 1.34 → 0.44 → 0.23 → 0.14 → 0.095 → 0.070 →
0.057. The first step started at 8.3.

## 5. Synthetic phone captures (`augment.py`)

Applied in this order, each value drawn uniformly at random:

| Step | Range | Simulates |
|---|---|---|
| Perspective: each corner moved independently | ±4% of width/height | Imperfect crop, card not flat/square to the camera |
| Brightness | ×0.6–1.3 | Exposure |
| Contrast | ×0.7–1.2 | |
| Saturation | ×0.7–1.3 | |
| Per-channel tint | ×0.9–1.1 each of R, G, B | White balance, warm/cool lights |
| Glare (60% of samples) | white blurred ellipse, radius 15–40% of width, opacity 35–70% | Light reflecting off the sleeve/holo |
| Gaussian blur | radius 0–2 px | Focus, motion |
| Downscale | height 280–460 px | Camera crop resolution |
| JPEG | quality 40–85 | Compression |

**Evaluation only** (`augment_loose.py`, `eval_synthetic.py --loose`), never used
in training: "loose framing". The card is scaled to 80–104% of the frame,
moved off-centre by ±6%, rotated ±6°, given extra ±3% corner jitter, and
placed on a random textured background. This simulates a user who doesn't line
the card up with the guide exactly.

## 6. Results

Synthetic test: random cards, distorted as above, searched against the full
20,723-card index. "Art top-1" counts a same-artwork reprint as correct.

| Model | Top-1 | Top-5 | Art top-1 |
|---|---|---|---|
| Pretrained, no fine-tuning | 76.4% | 87.2% | 79.2% |
| Fine-tuned, final (PyTorch) | 97.9% | 100% | 99.7% |
| Final, loose framing | 97.6% | 100% | 99.7% |
| Final, ONNX full (fp32) | 98.4% | 100% | 100% |
| Final, ONNX int8 | 98.2% | 100% | 100% |

After the extra cards were appended (26,675 cards, same model, int8): top-1 91.3%.
For the original cards, 93.3% exact and 99.6% same name. For the added cards, 79.7%
exact and 100% same name. Most added cards are stamped or gold-border reprints of
existing art, so the right card is found but the exact printing often has to be
picked from the alternatives.

Almost all remaining misses are reprints with near-identical art (e.g.
`sv8-57 → me2pt5-57`). An image model can't separate those reliably, which
is why the app shows alternatives.

**Real-world** (Galaxy Z Fold7, by hand): matches at **≥ 60% were always the
right card**. Pointing at random things scores around **30%**. Full and int8
showed similar accuracy, and int8 was much faster. So the app now uses:

| Top score | App behaviour |
|---|---|
| < 50% | No new card is shown: "no card recognised" |
| 50–60% | Locks (2 frames agree), shown with "low confidence, check the card" (amber), never auto-added |
| ≥ 60% | Locks, shown as a match (green), auto-added after the configured time |

Once a card is locked it stays on screen (no flicker) until the top score is
below **40%** for 2 frames (the card left the frame), or a different card
scores higher than the locked card's best for 2 frames. Auto-add happens once
per lock; Settings → Auto-add picks Off / 1 / 2 / 3 / 5 s (default 2 s).
The other candidates keep the order they first appeared in (new ones are
appended). ✓ adds now; ✕ opens a list of the detected card and its candidates
(Cancel restarts the auto-add timer; picking after an auto-add swaps the card).
Logic: `CardLock.kt`, tests in `CardLockTest`.

## 7. Exported models: full vs int8 (and iPhone)

`export.py` wraps the backbone with the mean/std and L2 normalisation and
exports it three ways. Parity is checked on 100 cards.

| | Full (`embedder.onnx`) | int8 (`embedder.int8.onnx`) | iPhone (`Embedder.mlpackage`) |
|---|---|---|---|
| Runtime | ONNX Runtime (Android) | ONNX Runtime (Android) | Core ML (iOS 17+) |
| Weights | float32 | **int8** for every MatMul/Gemm (attention + MLP); everything else float32 | float16 |
| Activations | float32 | quantised to 8-bit **on the fly** per inference (dynamic quantisation), with a scale computed from each input | float16 |
| File size | 86.8 MB | **22.5 MB** (3.9× smaller) | 41 MB |
| Synthetic top-1 / art top-1 | 98.4% / 100% | 98.2% / 100% | same top-1 as the index for 100% of parity cards |
| Top score when correct (p10 / median) | 0.77 / 0.85 | 0.73 / 0.82 | – |
| Speed on device | baseline | much faster (int8 math, a quarter of the memory traffic) | can use the Neural Engine |
| In the app | Settings → "Full (fp32)" | **Default.** Settings → "Fast (int8)" | – (no iOS app yet) |

How int8 was made: `onnxruntime.quantization.quantize_dynamic(...,
weight_type=QInt8)`. It's **post-training quantisation**. There's no
retraining and no calibration images. Each weight matrix is mapped to 8-bit
integers with one scale per tensor. At run time, ONNX Runtime quantises each
layer's input, does the multiply in integers, and converts back to float.
LayerNorm, softmax, GELU and the final normalisation stay in float32.

Things to know:

- **Both phone models search the same index**, built with the full-precision
  PyTorch model. With int8, a photo's embedding is slightly "off" from the index
  it's compared against. That's why int8 scores run about 0.03 lower on correct
  matches (p10 0.73 vs 0.77). Accuracy is almost unchanged. The 50%/60%
  thresholds came from testing both models and work for both.
- Core ML fp16 vs PyTorch: cosine ≥ 0.9997. Android Kotlin pipeline vs
  Python: cosine 0.9999999–1.0000001 (`ParityTest`, run on every build).
- Possible improvements to int8 later: static quantisation with calibration
  images, or an int8 index built *with* the int8 model. Neither is needed at
  current accuracy.

## 8. Reproducing

```sh
cd ml
uv run python download_images.py      # NAS master copies (optional for training)
uv run python build_cache.py          # cache/train working copies
uv run python embed.py                # pretrained index (needed by art_groups)
uv run python art_groups.py
uv run python finetune.py             # --epochs 8 --batch-size 48 --lr 2e-5
T=dinov2-s14-224x308-ft; C=../embeddings/$T/backbone.pt
uv run python embed.py --checkpoint $C --tag $T
uv run python eval_synthetic.py --tag $T --checkpoint $C [--loose] [--samples 1000]
uv run python export.py --tag $T --checkpoint $C
uv run python eval_synthetic.py --tag $T --onnx ../embeddings/$T/embedder.int8.onnx
uv run python export_android.py --tag $T   # copies models + index into the app
```

Library versions: Python 3.12, torch 2.15, timm, onnxruntime 1.31.1, coremltools 9.0
(on the Android side: onnxruntime-android 1.31.1).
