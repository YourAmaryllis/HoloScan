"""Fine-tune the DINOv2 backbone so phone-like captures land next to their clean scan.

Each art group (see art_groups.py) is one class; every step shows the model a
fresh synthetic phone capture of a card and an ArcFace loss pulls it toward its
class. Output size stays 384, so the result is a drop-in replacement for the
pretrained backbone in embed.py / export.py.

Training reads the local 336x462 working copies (build_cache.py), so the GPU
isn't waiting on network reads.
"""
import argparse
import json
import math
import random

import torch
import torch.nn.functional as F
from PIL import Image
from torch.utils.data import DataLoader, Dataset

from augment import phone_like
from config import EMBEDDINGS_DIR, LOCAL_CACHE_DIR
from embed import MODEL_TAG, card_images, create_model, to_tensor

FT_TAG = MODEL_TAG + "-ft"
FROZEN_BLOCKS = 6  # of 12; earlier blocks keep their general-purpose features


class PhoneCaptures(Dataset):
    def __init__(self, items, epoch_seed):
        self.items = items  # (cache path, class index)
        self.epoch_seed = epoch_seed

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        path, label = self.items[i]
        rng = random.Random(self.epoch_seed * 1_000_003 + i)
        return to_tensor(phone_like(Image.open(path).convert("RGB"), rng)), label


class ArcFace(torch.nn.Module):
    def __init__(self, dim, classes, scale=30.0, margin=0.25):
        super().__init__()
        self.weight = torch.nn.Parameter(torch.randn(classes, dim) * 0.01)
        self.scale, self.margin = scale, margin

    def forward(self, emb, labels):
        cos = F.linear(F.normalize(emb), F.normalize(self.weight)).clamp(-1 + 1e-6, 1 - 1e-6)
        target = torch.cos(torch.acos(cos.gather(1, labels[:, None])) + self.margin)
        logits = cos.scatter(1, labels[:, None], target) * self.scale
        return F.cross_entropy(logits, labels)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch-size", type=int, default=48)
    ap.add_argument("--lr", type=float, default=2e-5)
    args = ap.parse_args()

    groups_file = EMBEDDINGS_DIR / "art_groups.json"
    group = json.loads(groups_file.read_text()) if groups_file.exists() else {}
    cards = [cid for cid, _ in card_images()]
    classes = sorted({group.get(cid, cid) for cid in cards})
    class_idx = {g: i for i, g in enumerate(classes)}
    items = [(LOCAL_CACHE_DIR / f"{cid}.jpg", class_idx[group.get(cid, cid)]) for cid in cards]
    print(f"{len(items)} cards, {len(classes)} classes", flush=True)

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model = create_model(device).train()
    for p in [*model.patch_embed.parameters(), model.cls_token, model.pos_embed,
              *(q for b in model.blocks[:FROZEN_BLOCKS] for q in b.parameters())]:
        p.requires_grad = False
    head = ArcFace(model.num_features, len(classes)).to(device)
    # Start each class weight at its clean scan's embedding so the head isn't random.
    with torch.no_grad():
        model.eval()
        for k in range(0, len(items), 128):
            batch = torch.stack([to_tensor(Image.open(p).convert("RGB")) for p, _ in items[k:k + 128]])
            labels = torch.tensor([c for _, c in items[k:k + 128]], device=device)
            head.weight[labels] = F.normalize(model(batch.to(device)))
        model.train()

    opt = torch.optim.AdamW([
        {"params": [p for p in model.parameters() if p.requires_grad], "lr": args.lr},
        {"params": head.parameters(), "lr": args.lr * 50},
    ], weight_decay=0.05)
    steps = args.epochs * math.ceil(len(items) / args.batch_size)
    sched = torch.optim.lr_scheduler.OneCycleLR(
        opt, max_lr=[args.lr, args.lr * 50], total_steps=steps, pct_start=0.05)

    out = EMBEDDINGS_DIR / FT_TAG
    out.mkdir(parents=True, exist_ok=True)
    for epoch in range(args.epochs):
        loader = DataLoader(PhoneCaptures(items, epoch), batch_size=args.batch_size, shuffle=True,
                            num_workers=8, persistent_workers=False, drop_last=True)
        total = 0.0
        for step, (x, y) in enumerate(loader):
            loss = head(model(x.to(device)), y.to(device))
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item()
            if step % 50 == 0:
                print(f"epoch {epoch + 1}/{args.epochs} step {step}/{len(loader)} loss {loss.item():.3f}", flush=True)
        print(f"epoch {epoch + 1} mean loss {total / len(loader):.3f}", flush=True)
        torch.save(model.state_dict(), out / "backbone.pt")
    print(f"saved {out / 'backbone.pt'}", flush=True)


if __name__ == "__main__":
    main()
