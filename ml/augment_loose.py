"""Phone captures with loose framing: the card doesn't exactly fill the guide.

phone_like() assumes the crop is the card give or take ~4% per corner. Real
users hold the card a little small, off-centre or rotated, so the crop also
shows table/hand. This places the card at 80-104% of the frame with an offset
and up to +-6 degrees of rotation over a textured background, then applies
the same photometric degradations as phone_like().
"""
import math

import numpy as np
from PIL import Image, ImageFilter
from torchvision.transforms import functional as TF

from augment import phone_like


def background(w, h, rng):
    base = np.array([rng.uniform(0, 255) for _ in range(3)])
    noise = np.random.default_rng(rng.randrange(1 << 30)).normal(0, rng.uniform(5, 40), (h // 8 + 1, w // 8 + 1, 3))
    tex = Image.fromarray(np.clip(base + noise, 0, 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)
    return tex.filter(ImageFilter.GaussianBlur(rng.uniform(0, 3)))


def loose_frame(img, rng):
    w, h = img.size
    scale = rng.uniform(0.80, 1.04)
    angle = math.radians(rng.uniform(-6, 6))
    cx = w / 2 + rng.uniform(-0.06, 0.06) * w
    cy = h / 2 + rng.uniform(-0.06, 0.06) * h
    cos, sin = math.cos(angle), math.sin(angle)
    corners = [[0, 0], [w, 0], [w, h], [0, h]]
    moved = []
    for x, y in corners:
        dx, dy = (x - w / 2) * scale, (y - h / 2) * scale
        jx, jy = rng.uniform(-0.03, 0.03) * w, rng.uniform(-0.03, 0.03) * h
        moved.append([cx + dx * cos - dy * sin + jx, cy + dx * sin + dy * cos + jy])
    rgba = TF.perspective(img.convert("RGBA"), corners, moved, fill=[0, 0, 0, 0])
    out = background(w, h, rng)
    out.paste(rgba, (0, 0), rgba)
    return out


def phone_like_loose(img, rng):
    # phone_like adds its own small corner jitter on top, like a real crop would.
    return phone_like(loose_frame(img, rng), rng)
