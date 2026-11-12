#!/bin/sh
# Downloads the model files that are too large for git from the GitHub release.
#   scripts/fetch_models.sh          # phone models -> android/app/src/main/assets/ (needed to build the app)
#   scripts/fetch_models.sh --all    # also the PyTorch checkpoint, fp32 index and Core ML model -> embeddings/
set -eu
TAG="${HOLOSCAN_RELEASE:-v1.0}"
BASE="https://github.com/YourAmaryllis/HoloScan/releases/download/$TAG"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets"
EMB="$ROOT/embeddings/dinov2-s14-224x308-ft"

get() {  # get <file> <dir>
    mkdir -p "$2"
    echo "$1 -> $2"
    curl -fL --retry 3 -o "$2/$1.part" "$BASE/$1" && mv "$2/$1.part" "$2/$1"
}

get embedder.int8.onnx "$ASSETS"
get embedder.onnx "$ASSETS"
if [ "${1:-}" = "--all" ]; then
    for f in embedder.onnx embedder.int8.onnx backbone.pt embeddings.f32.npy Embedder.mlpackage.zip; do
        get "$f" "$EMB"
    done
    (cd "$EMB" && rm -rf Embedder.mlpackage && unzip -q Embedder.mlpackage.zip && rm Embedder.mlpackage.zip)
fi
