"""Baseline: RapidOCR (Python, onnxruntime) on the same ONNX files as ocr-pro.

    pip install rapidocr==3.10.0 onnxruntime==1.31.0
    python bench/run-rapidocr.py bench/ref bench/data bench/out/rapidocr [models]

Writes <name>.txt per reference, one detected box per line in RapidOCR's order,
and prints the per-page time after one warm-up page.
"""
import sys
import time
from pathlib import Path

from rapidocr import OCRVersion, RapidOCR

EXTS = (".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif")


def main():
    ref_dir, image_dir, out_dir = (Path(a) for a in sys.argv[1:4])
    models = Path(sys.argv[4]) if len(sys.argv) > 4 else Path("models")
    out_dir.mkdir(parents=True, exist_ok=True)
    # Same files and DB settings as OcrConfig.defaults: det limit 2048 (max side), thresh 0.35,
    # the 80x160 text-line orientation model, PP-OCRv6 small recognition. No pre-shrink of the page.
    engine = RapidOCR(params={
        "Global.max_side_len": 100000,
        "Global.log_level": "error",
        "Det.model_path": str(models / "ch_PP-OCRv5_det_mobile.onnx"),
        "Det.limit_side_len": 2048,
        "Det.limit_type": "max",
        "Det.thresh": 0.35,
        "Cls.model_path": str(models / "ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx"),
        "Cls.ocr_version": OCRVersion.PPOCRV5,  # selects the 80x160 input of this model
        "Rec.model_path": str(models / "PP-OCRv6_rec_small.onnx"),
        "Rec.rec_keys_path": str(models / "ppocrv6_dict.txt"),
    })
    names = sorted(p.stem for p in ref_dir.glob("*.txt"))
    images = []
    for name in names:
        found = [image_dir / (name + e) for e in EXTS if (image_dir / (name + e)).is_file()]
        if not found:
            sys.exit(f"no image for {name} in {image_dir}")
        images.append(found[0])
    engine(str(images[0]))
    for name, image in zip(names, images):
        start = time.perf_counter()
        result = engine(str(image))
        millis = (time.perf_counter() - start) * 1000
        lines = list(result.txts) if result.txts else []
        (out_dir / (name + ".txt")).write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{name}\t{millis:.0f} ms", flush=True)


if __name__ == "__main__":
    main()
