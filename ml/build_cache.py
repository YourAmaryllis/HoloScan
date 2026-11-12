"""Build the local working copy of every card image used for embedding and training.

Fetches each card's *large* image from the image CDN (the same file the NAS
holds; reading it back through the mount takes seconds per image), checks it
against the server's Content-Length, fills transparent corners and resizes it
to 336x462 into cache/train/<card_id>.jpg. Re-runnable: existing files are kept.
"""
import io
import json
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from PIL import Image

from config import DATA_DIR, LOCAL_CACHE_DIR
from embed import BACKGROUND

CACHE_W, CACHE_H = 336, 462
WORKERS = 12
session = requests.Session()
session.headers["User-Agent"] = "HoloScan/0.1 (personal research)"


def fill_alpha(img):
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        bg = Image.new("RGBA", img.size, BACKGROUND + (255,))
        img = Image.alpha_composite(bg, img)
    return img.convert("RGB")


def one(card):
    dest = LOCAL_CACHE_DIR / f"{card['id']}.jpg"
    if dest.exists():
        return "skipped"
    for _ in range(3):
        try:
            r = session.get(card["images"]["large"], timeout=30)
            if r.status_code == 404:
                return "404"
            r.raise_for_status()
            expected = r.headers.get("Content-Length")
            if expected and int(expected) != len(r.content):
                continue
            img = fill_alpha(Image.open(io.BytesIO(r.content)))
            img.resize((CACHE_W, CACHE_H), Image.LANCZOS).save(dest, quality=95)
            return "ok"
        except (requests.RequestException, OSError):
            continue
    return "error"


def main():
    LOCAL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cards = [c for f in sorted((DATA_DIR / "cards" / "en").glob("*.json")) for c in json.loads(f.read_text())]
    counts = {}
    with ThreadPoolExecutor(WORKERS) as pool:
        futures = [pool.submit(one, c) for c in cards]
        for i, fut in enumerate(as_completed(futures), 1):
            s = fut.result()
            counts[s] = counts.get(s, 0) + 1
            if i % 1000 == 0:
                print(f"{i}/{len(cards)} {counts}", flush=True)
    print(f"done {counts}", flush=True)


if __name__ == "__main__":
    main()
