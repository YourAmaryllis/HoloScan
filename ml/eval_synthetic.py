"""Measure retrieval accuracy on synthetically degraded card images.

Each sampled card is distorted to look roughly like a phone capture (slight
perspective, imperfect crop, color cast, blur, glare, JPEG), embedded, and
looked up in the index. Reports top-1 / top-5 accuracy, plus "art top-1": the
top match is the right card or a reprint with the same artwork (see
art_groups.py), which on-device text recognition of the set/number resolves.
"""
import argparse
import json
import random

import numpy as np
import torch

from augment import phone_like
from augment_loose import phone_like_loose
from config import EMBEDDINGS_DIR
from embed import MEAN, MODEL_TAG, STD, card_images, create_model, embed_batch, load_rgb, to_tensor


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=1000)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint")
    ap.add_argument("--tag", default=MODEL_TAG)
    ap.add_argument("--loose", action="store_true", help="loose framing (augment_loose.py)")
    ap.add_argument("--onnx", help="embed queries with this ONNX model (as the phone does) instead of PyTorch")
    args = ap.parse_args()

    out = EMBEDDINGS_DIR / args.tag
    index = torch.from_numpy(np.load(out / "embeddings.f16.npy").astype(np.float32))
    ids = json.loads((out / "ids.json").read_text())
    pos = {cid: i for i, cid in enumerate(ids)}
    groups_file = EMBEDDINGS_DIR / "art_groups.json"
    group = json.loads(groups_file.read_text()) if groups_file.exists() else {}

    distort = phone_like_loose if args.loose else phone_like
    rng = random.Random(args.seed)
    paths = dict(card_images())
    sample = rng.sample(ids, min(args.samples, len(ids)))

    if args.onnx:
        import onnxruntime as ort
        sess = ort.InferenceSession(args.onnx)
        mean, std = torch.tensor(MEAN)[:, None, None], torch.tensor(STD)[:, None, None]

        def embed(batch):  # the ONNX graph normalizes internally; it takes [0,1] pixels
            x = (batch * std + mean).numpy()
            return torch.from_numpy(np.concatenate([sess.run(None, {"image": x[i:i + 1]})[0] for i in range(len(x))]))
    else:
        device = "mps" if torch.backends.mps.is_available() else "cpu"
        model = create_model(device, args.checkpoint)

        def embed(batch):
            return torch.from_numpy(embed_batch(model, batch, device))
    top1 = top5 = art1 = 0
    misses = []
    hit_scores, miss_scores = [], []
    for k in range(0, len(sample), 32):
        chunk = sample[k:k + 32]
        batch = torch.stack([to_tensor(distort(load_rgb(paths[cid]), rng)) for cid in chunk])
        scores, best = (embed(batch) @ index.T).topk(5, dim=1)
        for cid, row, score in zip(chunk, best.tolist(), scores[:, 0].tolist()):
            hit = pos[cid]
            top1 += row[0] == hit
            top5 += hit in row
            art1 += group.get(cid, cid) == group.get(ids[row[0]], ids[row[0]])
            (hit_scores if row[0] == hit else miss_scores).append(score)
            if row[0] != hit:
                misses.append((cid, ids[row[0]]))

    n = len(sample)
    print(f"samples={n} top1={top1 / n:.1%} top5={top5 / n:.1%} art_top1={art1 / n:.1%}")
    for name, sc in (("correct", hit_scores), ("wrong", miss_scores)):
        if sc:
            print(f"top-1 score when {name}: p10/p50/p90 = {np.percentile(sc, [10, 50, 90]).round(3)}")
    print("example misses (true -> predicted):")
    for t, p in misses[:15]:
        print(f"  {t} -> {p}")


if __name__ == "__main__":
    main()
