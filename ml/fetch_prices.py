"""Price snapshot from TCGdex, compiled into the app as prices.json.

Run locally (for now) instead of every phone calling TCGdex: one machine makes
~20k requests, the phones make none. Later a Supabase function does this job
and the app's PriceSource switches from the bundled file to the backend.

TCGdex has no bulk price endpoint, so each card is fetched once:
  https://api.tcgdex.net/v2/en/cards/<tcgdex id>   -> "pricing": {tcgplayer, cardmarket}
Responses are cached under cache/prices/, so a re-run only refetches cards older
than --max-age hours (resumable after an interruption).

Output (default android/app/src/main/assets/prices.json):
  {"updated": ..., "cards": {"<our id>": {"tcgdex": "...",   # or "tcgplayer": [groupId, productId]
      "usd": {"normal": 0.27, "reverse-holofoil": 0.43, ...},   # TCGplayer market (else mid)
      "eur": {"trend": 0.08, "avg30": 0.12, "trend-holo": 0.44, ...}}}}   # Cardmarket
"""
import argparse
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

import requests

from config import CACHE_ROOT, PROJECT_ROOT, all_sets, cards_by_set

API = "https://api.tcgdex.net/v2/en"
PRICE_CACHE = CACHE_ROOT / "prices"
DEFAULT_OUT = PROJECT_ROOT / "android" / "app" / "src" / "main" / "assets" / "prices.json"
HEADERS = {"User-Agent": "HoloScan price snapshot (personal project)"}

# Our set id -> TCGdex set id, where the names differ.
SET_OVERRIDES = {
    "base1": "base1", "hgss1": "hgss1", "hgss2": "hgss2", "hgss3": "hgss3", "hgss4": "hgss4",
    "fut20": "fut2020", "svp": "svp", "sve": "sve", "me55c": "30th-c",
}
EUR_FIELDS = ["avg", "low", "trend", "avg7", "avg30"]


def norm_name(s):
    s = s.lower().replace("&", "and").replace("pokémon", "pokemon")
    return re.sub(r"[^a-z0-9]", "", s)


def norm_number(s):
    s = s.lower()
    return s.lstrip("0") or "0"


def get_json(session, url, tries=4):
    for attempt in range(tries):
        try:
            r = session.get(url, headers=HEADERS, timeout=30)
            if r.status_code == 404:
                return None
            r.raise_for_status()
            return r.json()
        except requests.RequestException:
            if attempt == tries - 1:
                raise
            time.sleep(2 ** attempt)


def cached(session, path, url, max_age_h):
    if path.exists() and time.time() - path.stat().st_mtime < max_age_h * 3600:
        return json.loads(path.read_text())
    data = get_json(session, url)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data))
    return data


def map_cards(session, max_age_h):
    """Our card id -> TCGdex card id, via set names and card numbers."""
    ours = all_sets()
    by_set = cards_by_set()
    theirs = get_json(session, f"{API}/sets")
    by_name = {}
    for t in theirs:
        by_name.setdefault(norm_name(t["name"]), []).append(t["id"])
    mapping, missing_sets, missing_cards = {}, [], 0
    for s in ours:
        tid = SET_OVERRIDES.get(s["id"])
        if tid is None:
            hits = by_name.get(norm_name(s["name"]), [])
            tid = hits[0] if len(hits) == 1 else None
        if tid is None:
            missing_sets.append(s["id"])
            continue
        detail = cached(session, PRICE_CACHE / "sets" / f"{tid}.json", f"{API}/sets/{tid}", max_age_h * 7)
        local = {norm_number(c["localId"]): c["id"] for c in (detail or {}).get("cards", [])}
        for card in by_set.get(s["id"], []):
            hit = local.get(norm_number(card["number"]))
            if hit:
                mapping[card["id"]] = hit
            else:
                missing_cards += 1
    return mapping, missing_sets, missing_cards


def compact(pricing):
    out = {}
    tp = (pricing or {}).get("tcgplayer") or {}
    usd = {}
    for variant, p in tp.items():
        if isinstance(p, dict):
            v = p.get("marketPrice") or p.get("midPrice")
            if v:
                usd[variant] = round(v, 2)
    if usd:
        out["usd"] = usd
    cm = (pricing or {}).get("cardmarket") or {}
    eur = {}
    for f in EUR_FIELDS:
        for key in (f, f + "-holo"):
            if cm.get(key):
                eur[key] = round(cm[key], 2)
    if eur:
        out["eur"] = eur
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-age", type=float, default=20, help="hours before a cached card is refetched")
    ap.add_argument("--workers", type=int, default=4, help="parallel requests; keep it polite")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args()

    session = requests.Session()
    mapping, missing_sets, missing_cards = map_cards(session, args.max_age)
    print(f"mapped {len(mapping)} cards; unmapped sets {missing_sets}; unmapped cards {missing_cards}", flush=True)

    def fetch(our_id, tid):
        return our_id, tid, cached(session, PRICE_CACHE / "cards" / f"{tid}.json", f"{API}/cards/{tid}", args.max_age)

    cards, failed = {}, []
    with ThreadPoolExecutor(args.workers) as pool:
        futures = [pool.submit(fetch, o, t) for o, t in mapping.items()]
        for n, f in enumerate(as_completed(futures), 1):
            try:
                our_id, tid, data = f.result()
            except Exception as e:  # keep going; failures are listed at the end
                failed.append(str(e))
                continue
            entry = compact((data or {}).get("pricing"))
            if entry:
                cards[our_id] = {"tcgdex": tid, **entry}
            if n % 1000 == 0:
                print(f"{n}/{len(futures)} fetched, {len(cards)} with prices", flush=True)

    # Cards TCGdex doesn't price but TCGplayer lists (extra_sets.py records their
    # productId and groupId): TCGplayer prices straight from TCGCSV, one request per group.
    group_of_set = {x["id"]: x.get("tcgcsvGroup") for x in all_sets()}
    want = {}
    for set_id, set_cards in cards_by_set().items():
        for card in set_cards:
            tp = card.get("tcgplayer")
            group = tp and (tp.get("groupId") or group_of_set.get(set_id))
            if group and "usd" not in cards.get(card["id"], {}):
                want.setdefault(group, []).append(card)
    filled = 0
    for group, group_cards in want.items():
        by_product = {}
        for p in get_json(session, f"https://tcgcsv.com/tcgplayer/3/{group}/prices")["results"]:
            v = p.get("marketPrice") or p.get("midPrice")
            if v:
                variant = p["subTypeName"].lower().replace(" ", "-")
                by_product.setdefault(p["productId"], {})[variant] = round(v, 2)
        for card in group_cards:
            pid = card["tcgplayer"]["productId"]
            usd = by_product.get(pid)
            if usd:
                # The app's price refresh uses [groupId, productId] to re-read TCGCSV.
                cards.setdefault(card["id"], {}).update(usd=usd, tcgplayer=[int(group), int(pid)])
                filled += 1
    print(f"{filled} prices from TCGCSV ({len(want)} groups)", flush=True)

    out = {"updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
           "source": "TCGdex (TCGplayer USD market, Cardmarket EUR); TCGCSV for extra sets", "cards": cards}
    with open(args.out, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {len(cards)} priced cards to {args.out}; {len(failed)} failed", flush=True)


if __name__ == "__main__":
    main()
