"""Fill gaps in pokemon-tcg-data from TCGCSV (a daily mirror of TCGplayer's catalogue).

Every TCGplayer "group" (set) is compared with our data, and cards we don't have
are added in the pokemon-tcg-data layout under cache/data_extra/ (mirrored to the
NAS), each with its TCGplayer productId and groupId:

- a group that matches one of our sets (e.g. "SV: Scarlet & Violet Promo Cards" =
  svp) only adds the numbers we're missing, as <set>-<number> cards of that set;
- a group with no counterpart becomes a new set, "tcg<groupId>", e.g. Prize Pack
  Series Cards; its cards are "tcg<groupId>-<productId>" (numbers repeat inside
  such groups).

Tiers (cumulative, --tier N adds tiers 1..N):
  1  missing pictures for cards we have, promo/energy sets we lack (Mega
     Evolution promos and energies), and the numbers missing from sets we have
  2  every other group: stamped reprints (Prize Pack, Battle Academy, Trick or
     Trade, League, Professor Program, Deck/Blister exclusives), Trainer Kits,
     regional and restaurant promos, ...
  3  World Championship Decks, Jumbo cards, Miscellaneous Cards & Products

Images: the TCGplayer product image (658x920 JPEG) goes to the NAS as the
original (images/<set>/<id>.jpg) and to cache/train/ as the 336x462 working copy.
Re-runnable: existing files are kept, and group listings are cached for a day.

TCGCSV endpoints (Pokémon = category 3):
  https://tcgcsv.com/tcgplayer/3/groups                 groups (sets)
  https://tcgcsv.com/tcgplayer/3/<groupId>/products     cards, numbers, image URLs
  https://tcgcsv.com/tcgplayer/3/<groupId>/prices       TCGplayer prices per variant
"""
import argparse
import io
import json
import re
import shutil
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import requests
from PIL import Image

from build_cache import CACHE_H, CACHE_W, fill_alpha
from config import (CACHE_ROOT, DATA_DIR, EXTRA_DATA_DIR, IMAGES_DIR, LOCAL_CACHE_DIR,
                    NAS_EXTRA_DATA_DIR, all_sets, cards_by_set)

TCGCSV = "https://tcgcsv.com/tcgplayer/3"
IMAGE = "https://tcgplayer-cdn.tcgplayer.com/product/{}_in_1000x1000.jpg"
LISTINGS = CACHE_ROOT / "tcgcsv"
PRICE_CACHE = CACHE_ROOT / "prices" / "cards"  # TCGdex responses from fetch_prices.py

# McDonald's sets added by name (pokemon-tcg-data stops at 2022).
NAMED_SETS = {
    23306: ("mcd23", "McDonald's Collection 2023", "2023/09/12"),
    24163: ("mcd24", "McDonald's Collection 2024", "2025/01/21"),
    24938: ("mcd26", "McDonald's Collection 2026", "2026/11/10"),  # US Happy Meal launch
}
# Groups that are one of our sets, where the TCGdex price data doesn't link enough
# cards to TCGplayer for the automatic match below.
GROUP_TO_SET = {
    1407: ["bwp"], 1418: ["basep"], 1423: ["np"], 1421: ["dpp"], 1453: ["hsp"], 1451: ["xyp"],
    1861: ["smp"], 2545: ["swshp"], 22872: ["svp"], 1729: ["g1"], 1465: ["bw11"], 24382: ["sve"],
    1455: ["bp"], 1433: ["ru1"], 2931: ["cel25c"], 2781: ["swsh45sv"], 2594: ["sma"],
    3020: ["swsh9tg"], 3068: ["swsh10tg"], 3172: ["swsh11tg"], 17674: ["swsh12tg"],
    17689: ["swsh12pt5gg"], 24837: ["me55c"], 1401: ["mcd11"], 1427: ["mcd12"], 1692: ["mcd14"],
    1694: ["mcd15"], 3087: ["mcd16"], 2148: ["mcd17"], 2364: ["mcd18"], 2555: ["mcd19"],
    2782: ["mcd21"], 3150: ["mcd22"], 1542: ["tk2a", "tk2b"], 1543: ["tk1a", "tk1b"],
}
TIER1_NEW_GROUPS = {24451, 24461}           # Mega Evolution promos, Mega Evolution energies
TIER3_GROUPS = {2282, 1528, 2374}           # World Championship Decks, Jumbo, Miscellaneous

session = requests.Session()
session.headers["User-Agent"] = "HoloScan/0.1 (personal research)"


def get(url, tries=4):
    for attempt in range(tries):
        try:
            r = session.get(url, timeout=60)
            r.raise_for_status()
            return r
        except requests.RequestException:
            if attempt == tries - 1:
                raise
            time.sleep(2 ** attempt)


def listing(name, url, max_age_h=24):
    path = LISTINGS / name
    if not path.exists() or time.time() - path.stat().st_mtime > max_age_h * 3600:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(get(url).content)
    return json.loads(path.read_text())["results"]


def norm_number(n):
    """'004/015' -> '4', 'BW01' -> 'bw1', 'RC05' -> 'rc5', 'TG07/TG30' -> 'tg7'."""
    n = n.split("/")[0].strip().lower()
    return re.sub(r"(\D*)0*(\d+)", r"\1\2", n) or "0"


def single_cards(group):
    """Products that are cards (they have a Number), as (product, extendedData)."""
    out = []
    for p in listing(f"products_{group}.json", f"{TCGCSV}/{group}/products"):
        ext = {e["name"]: e["value"] for e in p.get("extendedData", [])}
        if "Number" in ext:
            out.append((p, ext))
    return out


def linked_products():
    """TCGplayer productId -> our card id, from TCGdex's price data and earlier extra cards."""
    prices = CACHE_ROOT.parent / "android" / "app" / "src" / "main" / "assets" / "prices.json"
    ours_by_tcgdex = {v["tcgdex"]: k for k, v in json.loads(prices.read_text())["cards"].items() if "tcgdex" in v}
    linked = {}
    for f in PRICE_CACHE.glob("*.json"):
        d = json.loads(f.read_text()) or {}
        our = ours_by_tcgdex.get(d.get("id"))
        for v in ((d.get("pricing") or {}).get("tcgplayer") or {}).values():
            if our and isinstance(v, dict) and v.get("productId"):
                linked[v["productId"]] = our
    for cards in cards_by_set().values():
        for c in cards:
            if "tcgplayer" in c:
                linked[c["tcgplayer"]["productId"]] = c["id"]
    return linked


def tier_of(group, has_counterpart):
    if group in TIER3_GROUPS:
        return 3
    if has_counterpart or group in TIER1_NEW_GROUPS:
        return 1
    return 2


def plan(groups, linked, by_set):
    """For each group: (tier, set id, is_new_set, [(product, ext, card id)] to add)."""
    numbers = {s: {norm_number(c["number"]) for c in cards} for s, cards in by_set.items()}
    ids = {c["id"] for cards in by_set.values() for c in cards}
    work = []
    for g in groups:
        gid = g["groupId"]
        cards = single_cards(gid)
        if not cards:
            continue
        votes = Counter(linked[p["productId"]].rsplit("-", 1)[0] for p, _ in cards if p["productId"] in linked)
        targets = GROUP_TO_SET.get(gid) or [s for s, _ in votes.most_common(2)]
        if gid in NAMED_SETS:
            targets = [NAMED_SETS[gid][0]]
        new_set = not targets
        set_id = f"tcg{gid}" if new_set else targets[0]
        add = []
        for p, ext in cards:
            if p["productId"] in linked:
                continue
            n = norm_number(ext["Number"])
            if not new_set and any(n in numbers.get(t, ()) for t in targets):
                continue
            cid = f"{set_id}-{p['productId']}" if new_set else f"{set_id}-{ext['Number'].split('/')[0]}"
            if cid in ids:
                cid = f"{set_id}-{p['productId']}"
            ids.add(cid)
            add.append((p, ext, cid))
        if add:
            work.append((tier_of(gid, not new_set), set_id, new_set, g, add))
    return work


def save_image(set_id, card_id, product_id):
    work = LOCAL_CACHE_DIR / f"{card_id}.jpg"
    orig = IMAGES_DIR / set_id / f"{card_id}.jpg"
    if work.exists() and orig.exists():
        return True
    try:
        r = get(IMAGE.format(product_id))
    except requests.RequestException:
        return False
    expected = r.headers.get("Content-Length")
    if expected and int(expected) != len(r.content):
        return False
    try:
        img = fill_alpha(Image.open(io.BytesIO(r.content)))
    except OSError:
        return False
    if not orig.exists():
        orig.parent.mkdir(parents=True, exist_ok=True)
        orig.write_bytes(r.content)
    img.resize((CACHE_W, CACHE_H), Image.LANCZOS).save(work, quality=95)
    return True


def card_from_product(card_id, p, ext, group):
    name = re.sub(r"\s+-\s+[\w/]+$", "", p["name"])  # "Weedle - 1/12" -> "Weedle"
    card = {"id": card_id, "name": name, "number": ext["Number"].split("/")[0],
            "rarity": ext.get("Rarity", ""),
            "tcgplayer": {"productId": p["productId"], "groupId": group, "url": p["url"]},
            "images": {"large": IMAGE.format(p["productId"])}}
    if "HP" in ext:
        card["hp"] = ext["HP"]
    return card


def fill_missing_images(linked_by_set):
    """Cards in pokemon-tcg-data whose image is missing: take TCGplayer's by set + number."""
    done = []
    for f in sorted((DATA_DIR / "cards" / "en").glob("*.json")):
        for card in json.loads(f.read_text()):
            if (LOCAL_CACHE_DIR / f"{card['id']}.jpg").exists():
                continue
            for gid in linked_by_set.get(f.stem, []):
                hit = next((p for p, e in single_cards(gid)
                            if norm_number(e["Number"]) == norm_number(card["number"])), None)
                if hit and save_image(f.stem, card["id"], hit["productId"]):
                    done.append(card["id"])
                    break
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", type=int, default=1, choices=[1, 2, 3])
    ap.add_argument("--dry-run", action="store_true", help="only print what would be added")
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()

    groups = listing("groups.json", f"{TCGCSV}/groups")
    linked = linked_products()
    by_set = cards_by_set()
    work = [w for w in plan(groups, linked, by_set) if w[0] <= args.tier]
    print(f"{sum(len(w[4]) for w in work)} cards to add in {len(work)} groups (tiers 1-{args.tier})", flush=True)
    for tier, set_id, new_set, g, add in sorted(work, key=lambda w: (w[0], -len(w[4]))):
        print(f"  tier {tier}  {len(add):5d}  {set_id:12s} {'new' if new_set else 'gap'}  {g['name']}")
    if args.dry_run:
        return

    # Existing extra data is kept and extended.
    sets_file = EXTRA_DATA_DIR / "sets" / "en.json"
    sets = {s["id"]: s for s in (json.loads(sets_file.read_text()) if sets_file.exists() else [])}
    for tier, set_id, new_set, g, add in work:
        out = EXTRA_DATA_DIR / "cards" / "en" / f"{set_id}.json"
        existing = {c["id"]: c for c in (json.loads(out.read_text()) if out.exists() else [])}
        with ThreadPoolExecutor(args.workers) as pool:
            ok = list(pool.map(lambda a: save_image(set_id, a[2], a[0]["productId"]), add))
        for (p, ext, cid), good in zip(add, ok):
            if good:
                existing[cid] = card_from_product(cid, p, ext, g["groupId"])
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(list(existing.values()), indent=1, ensure_ascii=False))
        if new_set:
            name, released = g["name"], g["publishedOn"][:10].replace("-", "/")
            if g["groupId"] in NAMED_SETS:
                set_id, name, released = NAMED_SETS[g["groupId"]]
            sets[set_id] = {"id": set_id, "name": name, "series": "Other", "printedTotal": len(existing),
                            "total": len(existing), "releaseDate": released, "tcgcsvGroup": g["groupId"]}
        print(f"{set_id}: {sum(ok)}/{len(add)} added ({g['name']})", flush=True)
    sets_file.parent.mkdir(parents=True, exist_ok=True)
    sets_file.write_text(json.dumps(list(sets.values()), indent=1, ensure_ascii=False))

    linked_by_set = {}
    for g in groups:
        for t in GROUP_TO_SET.get(g["groupId"], []):
            linked_by_set.setdefault(t, []).append(g["groupId"])
    filled = fill_missing_images(linked_by_set)
    print(f"missing pictures filled: {filled}", flush=True)

    shutil.copytree(EXTRA_DATA_DIR, NAS_EXTRA_DATA_DIR, dirs_exist_ok=True)
    print(f"extra data mirrored to {NAS_EXTRA_DATA_DIR}", flush=True)


if __name__ == "__main__":
    main()
