import os
from pathlib import Path

# Master copies of card data and images (a NAS mount here); override with HOLOSCAN_NAS.
NAS_ROOT = Path(os.environ.get("HOLOSCAN_NAS", Path.home() / "nas/arthur/pokemon-tcg"))
NAS_DATA_DIR = NAS_ROOT / "data"  # PokemonTCG/pokemon-tcg-data checkout
IMAGES_DIR = NAS_ROOT / "images"  # images/<set_id>/<card_id>.<ext>, full-res originals

PROJECT_ROOT = Path(__file__).resolve().parent.parent
EMBEDDINGS_DIR = PROJECT_ROOT / "embeddings"
# Local working copies: reads through the NAS mount are too slow for training
# (seconds per image), so the pipeline reads these instead.
CACHE_ROOT = PROJECT_ROOT / "cache"
DATA_DIR = CACHE_ROOT / "data"           # same tarball as NAS_DATA_DIR
LOCAL_CACHE_DIR = CACHE_ROOT / "train"   # each card's large image resized to 336x462
DATA_TARBALL = "https://codeload.github.com/PokemonTCG/pokemon-tcg-data/tar.gz/refs/heads/master"

# Sets and images that pokemon-tcg-data doesn't have, taken from TCGCSV (TCGplayer's
# catalogue): same layout as DATA_DIR (sets/en.json, cards/en/<set>.json). See extra_sets.py.
EXTRA_DATA_DIR = CACHE_ROOT / "data_extra"
NAS_EXTRA_DATA_DIR = NAS_ROOT / "data_extra"


def card_files():
    """Every cards/en/<set>.json, pokemon-tcg-data first, then the extra sets."""
    for root in (DATA_DIR, EXTRA_DATA_DIR):
        yield from sorted((root / "cards" / "en").glob("*.json"))


def all_sets():
    import json
    sets = []
    for root in (DATA_DIR, EXTRA_DATA_DIR):
        f = root / "sets" / "en.json"
        if f.exists():
            sets += json.loads(f.read_text())
    return sets


def cards_by_set():
    """set id -> cards, merging pokemon-tcg-data with extra cards filed under the same set."""
    import json
    out = {}
    for f in card_files():
        out.setdefault(f.stem, []).extend(json.loads(f.read_text()))
    return out
