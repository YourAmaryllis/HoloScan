"""Synthetic "phone capture" degradations of clean card scans, shared by training and eval."""
import io

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
from torchvision.transforms import functional as TF


def glare(img, rng):
    w, h = img.size
    layer = Image.new("L", img.size, 0)
    cx, cy = rng.uniform(0, w), rng.uniform(0, h)
    r = rng.uniform(0.15, 0.4) * w
    ImageDraw.Draw(layer).ellipse((cx - r, cy - r * 0.6, cx + r, cy + r * 0.6), fill=int(rng.uniform(90, 180)))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.4))
    return Image.composite(Image.new("RGB", img.size, (255, 255, 255)), img, layer)


def phone_like(img, rng):
    w, h = img.size
    # Imperfect corner detection: each corner off by up to 4% of the card size.
    j = lambda: rng.uniform(-0.04, 0.04)
    corners = [[0, 0], [w, 0], [w, h], [0, h]]
    moved = [[x + j() * w, y + j() * h] for x, y in corners]
    img = TF.perspective(img, corners, moved, fill=[int(rng.uniform(0, 255))] * 3)
    img = ImageEnhance.Brightness(img).enhance(rng.uniform(0.6, 1.3))
    img = ImageEnhance.Contrast(img).enhance(rng.uniform(0.7, 1.2))
    img = ImageEnhance.Color(img).enhance(rng.uniform(0.7, 1.3))
    tint = np.array([rng.uniform(0.9, 1.1) for _ in range(3)])
    img = Image.fromarray(np.clip(np.asarray(img) * tint, 0, 255).astype(np.uint8))
    if rng.random() < 0.6:
        img = glare(img, rng)
    img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0, 2.0)))
    # Resolution of the flattened card crop on a phone, independent of source size.
    out_h = int(rng.uniform(280, 460))
    small = img.resize((int(out_h * w / h), out_h), Image.BILINEAR)
    buf = io.BytesIO()
    small.save(buf, "JPEG", quality=int(rng.uniform(40, 85)))
    return Image.open(buf).convert("RGB")
