"""Export the embedding model for the phones and check it matches PyTorch.

- ONNX  (embedder.onnx): Android via ONNX Runtime Mobile (also runs on iOS)
- Core ML (Embedder.mlpackage): iOS, can use the Neural Engine

Mean/std normalization and L2 normalization are baked into the graph, so the
app only resizes the flattened card to 224x308 and scales pixels to [0, 1].
"""
import argparse
import json

import coremltools as ct
import numpy as np
import onnxruntime as ort
import torch

from config import EMBEDDINGS_DIR
from embed import INPUT_H, INPUT_W, MEAN, MODEL_TAG, STD, card_images, create_model, load_rgb


class Embedder(torch.nn.Module):
    def __init__(self, backbone):
        super().__init__()
        self.backbone = backbone
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):  # x: [N, 3, 308, 224], RGB in [0, 1]
        feats = self.backbone((x - self.mean) / self.std)
        return torch.nn.functional.normalize(feats, dim=-1)


def unit_pixels(img):
    img = img.resize((INPUT_W, INPUT_H), resample=2)  # 2 = bilinear
    return np.asarray(img, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default=MODEL_TAG, help="folder under embeddings/ holding the index")
    ap.add_argument("--checkpoint", help="fine-tuned backbone weights (default: pretrained)")
    args = ap.parse_args()

    out = EMBEDDINGS_DIR / args.tag
    model = Embedder(create_model("cpu", args.checkpoint)).eval()
    example = torch.rand(1, 3, INPUT_H, INPUT_W)

    torch.onnx.export(model, example, out / "embedder.onnx", input_names=["image"],
                      output_names=["embedding"], dynamo=False, opset_version=17)

    # Smaller/faster variant for phones: int8 weights for the matrix multiplies.
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(out / "embedder.onnx", out / "embedder.int8.onnx", weight_type=QuantType.QInt8)

    traced = torch.jit.trace(model, example)
    mlmodel = ct.convert(
        traced,
        inputs=[ct.ImageType(name="image", shape=example.shape, scale=1 / 255.0,
                             color_layout=ct.colorlayout.RGB)],
        outputs=[ct.TensorType(name="embedding")],
        minimum_deployment_target=ct.target.iOS17,
        compute_precision=ct.precision.FLOAT16,
    )
    mlmodel.save(out / "Embedder.mlpackage")

    # Parity: same card images through PyTorch, ONNX Runtime and Core ML.
    index = np.load(out / "embeddings.f32.npy")
    ids = json.loads((out / "ids.json").read_text())
    pos = {cid: i for i, cid in enumerate(ids)}
    sess = ort.InferenceSession(str(out / "embedder.onnx"))
    rows = []
    for cid, path in list(card_images())[::max(1, len(ids) // 100)][:100]:
        img = load_rgb(path)
        x = unit_pixels(img)
        e_pt = model(torch.from_numpy(x)).detach().numpy()[0]
        e_onnx = sess.run(None, {"image": x})[0][0]
        e_ml = mlmodel.predict({"image": img.resize((INPUT_W, INPUT_H), resample=2)})["embedding"][0]
        e_ml = e_ml / np.linalg.norm(e_ml)
        rows.append((pos[cid], e_pt @ e_onnx, e_pt @ e_ml, e_onnx @ e_ml,
                     int(np.argmax(index @ e_onnx)), int(np.argmax(index @ e_ml))))

    r = np.array(rows)
    print(f"{len(r)} cards compared (cosine similarity, 1.0 = identical)")
    print(f"  pytorch vs onnx   : min {r[:, 1].min():.5f}  mean {r[:, 1].mean():.5f}")
    print(f"  pytorch vs coreml : min {r[:, 2].min():.5f}  mean {r[:, 2].mean():.5f}")
    print(f"  onnx    vs coreml : min {r[:, 3].min():.5f}  mean {r[:, 3].mean():.5f}")
    print(f"  top-1 match vs index: onnx {np.mean(r[:, 4] == r[:, 0]):.0%}, coreml {np.mean(r[:, 5] == r[:, 0]):.0%}")


if __name__ == "__main__":
    main()
