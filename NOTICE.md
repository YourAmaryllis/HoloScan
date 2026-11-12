Required Notice: Copyright 2026 Arthur Tsang (YourAmaryllis) (https://github.com/YourAmaryllis/HoloScan)

# Third-party notices

HoloScan's own code, model fine-tuning and artwork are licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). It builds on:

| Component | Use | License |
|---|---|---|
| [DINOv2](https://github.com/facebookresearch/dinov2) ViT-S/14 weights (Meta) | Base of the recognition model; HoloScan's model is a fine-tune of it | Apache-2.0 |
| [timm](https://github.com/huggingface/pytorch-image-models) | Model code for training and export | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | On-device inference | MIT |
| [PyTorch](https://pytorch.org), [coremltools](https://github.com/apple/coremltools), AndroidX / Jetpack Compose / CameraX | Training, export, app | BSD-3-Clause, BSD-3-Clause, Apache-2.0 |
| [PokemonTCG/pokemon-tcg-data](https://github.com/PokemonTCG/pokemon-tcg-data) | Card list (names, sets, numbers, rarities) in `cards.json` | No license stated; factual card metadata only |
| [TCGdex](https://tcgdex.dev) | Card id mapping and prices | MIT (cards-database) |
| [TCGCSV](https://tcgcsv.com) | TCGplayer product listings and prices | Public mirror of TCGplayer data |

The Apache-2.0 license of DINOv2 and timm applies to those components; the
fine-tuned weights published in the releases are a modified version of DINOv2.

Card images are not included in this repository or the releases. Card artwork,
Pokémon names and the Pokémon TCG are © and ™ Nintendo, Creatures Inc., GAME FREAK
inc. and The Pokémon Company. HoloScan is not affiliated with or endorsed by them.
