"""Download every card's high-res image from the pokemon-tcg-data JSON to the NAS.

Safe to re-run: a file already on disk is kept only if its size matches the
server's Content-Length, so partial or empty files are fetched again.
"""
import json
import shutil
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import urlparse

import requests

from config import DATA_DIR, IMAGES_DIR

WORKERS = 8
# The NAS mount stages writes in a local rclone cache before uploading; pause
# when the laptop disk gets low so the cache can drain instead of filling it.
MIN_FREE_BYTES = 5 * 1024**3
session = requests.Session()
session.headers["User-Agent"] = "HoloScan/0.1 (personal research)"


def load_cards():
    for f in sorted((DATA_DIR / "cards" / "en").glob("*.json")):
        set_id = f.stem
        for card in json.loads(f.read_text()):
            yield set_id, card


def target_path(set_id, card):
    url = card["images"]["large"]
    ext = Path(urlparse(url).path).suffix or ".png"
    return IMAGES_DIR / set_id / f"{card['id']}{ext}"


def wait_for_disk():
    while shutil.disk_usage(Path.home()).free < MIN_FREE_BYTES:
        print("low disk space, waiting for the NAS cache to upload...", flush=True)
        time.sleep(30)


def remote_size(url):
    r = session.head(url, timeout=30, allow_redirects=True)
    r.raise_for_status()
    size = r.headers.get("Content-Length")
    return int(size) if size else None


def fetch(url, dest):
    for attempt in range(4):
        try:
            if dest.exists():
                size = remote_size(url)
                local = dest.stat().st_size
                if local > 0 and (size is None or local == size):
                    return "skipped"
            wait_for_disk()
            r = session.get(url, timeout=30)
            if r.status_code == 404:
                return "404"
            r.raise_for_status()
            expected = r.headers.get("Content-Length")
            if expected and int(expected) != len(r.content):
                raise requests.RequestException("truncated download")
            tmp = dest.with_suffix(dest.suffix + ".part")
            tmp.write_bytes(r.content)
            tmp.rename(dest)
            return "ok"
        except requests.RequestException:
            time.sleep(2 ** attempt)
    return "error"


def main():
    jobs = []
    for set_id, card in load_cards():
        dest = target_path(set_id, card)
        dest.parent.mkdir(parents=True, exist_ok=True)
        jobs.append((card["id"], card["images"]["large"], dest))
    print(f"{len(jobs)} images to check", flush=True)

    failed = []
    skipped = 0
    with ThreadPoolExecutor(WORKERS) as pool:
        futures = {pool.submit(fetch, url, dest): (cid, url) for cid, url, dest in jobs}
        for i, fut in enumerate(as_completed(futures), 1):
            status = fut.result()
            skipped += status == "skipped"
            if status not in ("ok", "skipped"):
                failed.append((*futures[fut], status))
            if i % 500 == 0:
                print(f"{i}/{len(jobs)} checked, {skipped} already ok, {len(failed)} failed", flush=True)

    (IMAGES_DIR / "failed.json").write_text(json.dumps(failed, indent=1))
    print(f"finished: {len(jobs) - len(failed) - skipped} downloaded, {skipped} already ok, "
          f"{len(failed)} failed", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
