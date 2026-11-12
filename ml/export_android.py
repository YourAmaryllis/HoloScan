"""Package a model + card index into the Android app's assets.

android/models/src/main/assets/   (install-time asset pack)
  embedder.onnx      model: [1,3,308,224] RGB in [0,1] -> [1,384] L2-normalized
  embedder.int8.onnx same model with int8 weights (~4x smaller, usually faster)

android/app/src/main/assets/
  index.f16          float16 little-endian, rows aligned with cards.json
  cards.json         [{id,name,set,series,number,total,rarity,released,artist}, ...]
  model.json         tag, dim, count, input size

android/app/src/test/resources/parity/  (JVM unit test: Kotlin preprocessing + ORT
  must reproduce Python's embedding for the same image)
  <card_id>.rgb: "<width> <height>\n" then raw RGB bytes of the decoded working copy
  reference.txt: one line per card, "<card_id> <384 floats>"
"""
import argparse
import json
import shutil

import numpy as np
import onnxruntime as ort
from PIL import Image

from config import EMBEDDINGS_DIR, LOCAL_CACHE_DIR, PROJECT_ROOT, all_sets, card_files
from embed import INPUT_H, INPUT_W

ANDROID_APP = PROJECT_ROOT / "android" / "app" / "src"
PARITY_SAMPLES = 5


def card_rows(ids):
    sets = {s["id"]: s for s in all_sets()}
    cards = {}
    for f in card_files():
        set_id = f.stem
        for c in json.loads(f.read_text()):
            s = sets.get(set_id, {})
            cards[c["id"]] = {
                "id": c["id"],
                "name": c["name"],
                "set": s.get("name", set_id),
                "series": s.get("series", ""),
                "number": c.get("number", ""),
                "total": s.get("printedTotal") or s.get("total") or "",
                "rarity": c.get("rarity", ""),
                "released": s.get("releaseDate", ""),
                "artist": c.get("artist", ""),
            }
    return [cards[cid] for cid in ids]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True, help="folder under embeddings/, e.g. dinov2-s14-224x308-ft")
    args = ap.parse_args()

    src = EMBEDDINGS_DIR / args.tag
    ids = json.loads((src / "ids.json").read_text())
    emb = np.load(src / "embeddings.f16.npy")
    assets = ANDROID_APP / "main" / "assets"
    assets.mkdir(parents=True, exist_ok=True)

    models = ANDROID_APP.parent.parent / "models" / "src" / "main" / "assets"
    models.mkdir(parents=True, exist_ok=True)
    shutil.copy(src / "embedder.onnx", models / "embedder.onnx")
    shutil.copy(src / "embedder.int8.onnx", models / "embedder.int8.onnx")
    emb.astype("<f2").tofile(assets / "index.f16")
    (assets / "cards.json").write_text(json.dumps(card_rows(ids), separators=(",", ":")))
    (assets / "model.json").write_text(json.dumps(
        {"tag": args.tag, "dim": int(emb.shape[1]), "count": len(ids),
         "input_width": INPUT_W, "input_height": INPUT_H}, indent=1))

    parity = ANDROID_APP / "test" / "resources" / "parity"
    parity.mkdir(parents=True, exist_ok=True)
    sess = ort.InferenceSession(str(src / "embedder.onnx"))
    ref = {}
    available = [cid for cid in ids if (LOCAL_CACHE_DIR / f"{cid}.jpg").exists()]
    for cid in available[::max(1, len(available) // PARITY_SAMPLES)][:PARITY_SAMPLES]:
        full = Image.open(LOCAL_CACHE_DIR / f"{cid}.jpg").convert("RGB")
        (parity / f"{cid}.rgb").write_bytes(f"{full.width} {full.height}\n".encode() + full.tobytes())
        img = full.resize((INPUT_W, INPUT_H), Image.BILINEAR)
        x = np.asarray(img, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
        ref[cid] = sess.run(None, {"image": x})[0][0].tolist()
    (parity / "reference.txt").write_text(
        "".join(f"{cid} {' '.join(f'{v:.7g}' for v in vec)}\n" for cid, vec in ref.items()))
    print(f"exported {len(ids)} cards from {args.tag} to {assets}")


if __name__ == "__main__":
    main()
