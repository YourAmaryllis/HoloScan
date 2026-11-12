"""Compute one embedding per card image and write the index to embeddings/<model_tag>/.

The preprocessing here is the contract the iOS and Android apps must reproduce
exactly; it is written to preprocess.json next to the index.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import timm
import torch
from PIL import Image
from torch.utils.data import DataLoader, Dataset

from config import EMBEDDINGS_DIR, IMAGES_DIR, LOCAL_CACHE_DIR, card_files

MODEL_NAME = "vit_small_patch14_dinov2.lvd142m"
MODEL_TAG = "dinov2-s14-224x308"
# Card aspect is ~63x88mm; 224x308 keeps it and is a multiple of the 14px patch.
INPUT_W, INPUT_H = 224, 308
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)
# Transparent rounded corners are filled with mid-gray, a neutral stand-in for
# whatever background the phone sees behind a card's corners.
BACKGROUND = (128, 128, 128)

PREPROCESS = {
    "input_width": INPUT_W,
    "input_height": INPUT_H,
    "layout": "NCHW",
    "color": "RGB",
    "resize": "bilinear, full card stretched to input size, no crop",
    "alpha_fill_rgb": BACKGROUND,
    "scale": "pixel / 255",
    "mean": MEAN,
    "std": STD,
    "output": "CLS token, L2-normalized; compare with dot product",
}


def load_rgb(path):
    img = Image.open(path)
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGBA", img.size, BACKGROUND + (255,))
        img = Image.alpha_composite(bg, img)
    return img.convert("RGB")


def to_tensor(img):
    img = img.resize((INPUT_W, INPUT_H), Image.BILINEAR)
    x = torch.from_numpy(np.asarray(img, dtype=np.float32) / 255.0).permute(2, 0, 1)
    return (x - torch.tensor(MEAN)[:, None, None]) / torch.tensor(STD)[:, None, None]


def create_model(device, checkpoint=None):
    # Fixed input size: position embeddings are resized once at load time rather
    # than on every forward pass, which keeps the graph exportable to ONNX/Core ML.
    model = timm.create_model(MODEL_NAME, pretrained=True, num_classes=0, img_size=(INPUT_H, INPUT_W))
    if checkpoint:
        model.load_state_dict(torch.load(checkpoint, map_location="cpu"))
    return model.eval().to(device)


@torch.no_grad()
def embed_batch(model, batch, device):
    feats = model(batch.to(device))
    return torch.nn.functional.normalize(feats, dim=-1).cpu().numpy()


def card_images(local_cache=True):
    """(card_id, image_path) for every card with an image.

    By default uses the 336x462 working copies in cache/train/ (build_cache.py);
    local_cache=False reads the full-res originals off the NAS instead.
    """
    if local_cache:
        found = {p.stem: p for p in LOCAL_CACHE_DIR.glob("*.jpg")}
    else:
        found = {p.stem: p for p in IMAGES_DIR.glob("*/*") if p.suffix in (".png", ".jpg", ".webp")}
    for f in card_files():
        for card in json.loads(f.read_text()):
            if card["id"] in found:
                yield card["id"], found[card["id"]]


class CardDataset(Dataset):
    def __init__(self, items):
        self.items = items

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        return to_tensor(load_rgb(self.items[i][1]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--checkpoint", help="fine-tuned backbone weights (default: pretrained)")
    ap.add_argument("--tag", default=MODEL_TAG, help="output folder under embeddings/")
    ap.add_argument("--from-nas", action="store_true", help="read full-res originals off the NAS (slow)")
    ap.add_argument("--append", action="store_true",
                    help="keep the existing index and only embed cards that aren't in it yet")
    args = ap.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    out = EMBEDDINGS_DIR / args.tag
    items = list(card_images(not args.from_nas))
    old_ids, old_emb = [], np.zeros((0, 384), np.float32)
    if args.append and (out / "ids.json").exists():
        old_ids = json.loads((out / "ids.json").read_text())
        old_emb = np.load(out / "embeddings.f32.npy")
        known = set(old_ids)
        items = [it for it in items if it[0] not in known]
    print(f"{len(items)} card images, device={device}", flush=True)

    model = create_model(device, args.checkpoint)
    loader = DataLoader(CardDataset(items), batch_size=args.batch_size, num_workers=6)
    chunks = []
    for i, batch in enumerate(loader):
        chunks.append(embed_batch(model, batch, device))
        if i % 20 == 0:
            print(f"{(i + 1) * args.batch_size}/{len(items)}", flush=True)
    emb = np.concatenate([old_emb] + chunks)
    items = [(cid, None) for cid in old_ids] + items

    out.mkdir(parents=True, exist_ok=True)
    np.save(out / "embeddings.f32.npy", emb.astype(np.float32))
    np.save(out / "embeddings.f16.npy", emb.astype(np.float16))
    (out / "ids.json").write_text(json.dumps([cid for cid, _ in items]))
    (out / "preprocess.json").write_text(json.dumps(
        {"model": MODEL_NAME, "checkpoint": args.checkpoint, "dim": emb.shape[1], "count": len(items), **PREPROCESS}, indent=1))
    print(f"wrote {emb.shape} to {out}", flush=True)


if __name__ == "__main__":
    main()
