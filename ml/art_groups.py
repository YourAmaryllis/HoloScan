"""Group reprints that share the same artwork into one "art group".

Two cards are the same artwork when they have the same name and their clean
scans embed almost identically with the pretrained model. Fine-tuning treats a
group as one identity (the image can't separate them; the collector number and
set symbol can), and eval_synthetic.py reports accuracy per group.

Writes embeddings/art_groups.json: {card_id: group_id}, only for cards in a
group of two or more.
"""
import argparse
import json
from collections import defaultdict

import numpy as np

from config import EMBEDDINGS_DIR, card_files
from embed import MODEL_TAG


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threshold", type=float, default=0.95)
    args = ap.parse_args()

    src = EMBEDDINGS_DIR / MODEL_TAG
    emb = np.load(src / "embeddings.f32.npy")
    ids = json.loads((src / "ids.json").read_text())
    names = {}
    for f in card_files():
        for card in json.loads(f.read_text()):
            names[card["id"]] = card["name"]

    parent = list(range(len(ids)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    by_name = defaultdict(list)
    for i, cid in enumerate(ids):
        by_name[names[cid]].append(i)

    sims = []
    for members in by_name.values():
        if len(members) < 2:
            continue
        s = emb[members] @ emb[members].T
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                sims.append(s[a, b])
                if s[a, b] >= args.threshold:
                    parent[find(members[a])] = find(members[b])

    groups = defaultdict(list)
    for i in range(len(ids)):
        groups[find(i)].append(ids[i])
    out = {cid: members[0] for members in groups.values() if len(members) > 1 for cid in members}
    (EMBEDDINGS_DIR / "art_groups.json").write_text(json.dumps(out, indent=0))

    sims = np.array(sims)
    print("same-name pair similarity percentiles (50/90/95/99):",
          np.percentile(sims, [50, 90, 95, 99]).round(3))
    multi = [m for m in groups.values() if len(m) > 1]
    print(f"{len(ids)} cards -> {len(groups)} art groups; {len(multi)} groups hold {len(out)} reprinted cards")
    for m in sorted(multi, key=len, reverse=True)[:5]:
        print(f"  {names[m[0]]}: {m}")


if __name__ == "__main__":
    main()
