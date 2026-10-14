#!/usr/bin/env python3
"""Bar chart of character accuracy per document group, as a PNG for the README. Needs Pillow.

usage: chart.py <out.png> <title> <manifest.tsv> <label>=<score.tsv>[,<score.tsv>...] ...

Each score file is the per-document output of `ocr score` (name, chars, CER, ...). Several files
for one label are pooled, so one bar can cover two sets. Documents are grouped by the manifest's
`kind`. Accuracy is 1 - pooled CER of the group, so reading order counts.
"""
import sys

from PIL import Image, ImageDraw, ImageFont

from kinds import GROUPS, manifest_kinds

COLORS = ["#2563eb", "#94a3b8", "#f59e0b", "#10b981", "#ef4444"]
SCALE = 2


def font(size, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    for base in ("/usr/share/fonts/truetype/dejavu/", "/Library/Fonts/", ""):
        try:
            return ImageFont.truetype(base + name, size * SCALE)
        except OSError:
            continue
    return ImageFont.load_default()


def read_scores(paths):
    docs = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if cols[0] == "group":
                    break
                if len(cols) < 3:
                    continue
                try:
                    docs[cols[0]] = (int(cols[1]), float(cols[2]))
                except ValueError:
                    continue
    return docs


def accuracy(docs, names):
    chars = sum(docs[n][0] for n in names if n in docs)
    edits = sum(docs[n][0] * docs[n][1] for n in names if n in docs)
    return None if chars == 0 else max(0.0, 1.0 - edits / chars)


def main():
    out, title, manifest = sys.argv[1], sys.argv[2], sys.argv[3]
    kinds = manifest_kinds(manifest)
    engines = []
    for arg in sys.argv[4:]:
        label, paths = arg.split("=", 1)
        engines.append((label, read_scores(paths.split(","))))
    names = set(engines[0][1])
    rows = []
    for group, members in GROUPS:
        picked = sorted(n for n in names if kinds.get(n) in members)
        if picked:
            rows.append((f"{group} ({len(picked)})", picked))
    rows.append((f"All documents ({len(names)})", sorted(names)))

    s = SCALE
    bar_h, gap, group_gap, label_w, plot_w, top = 20, 4, 24, 290, 430, 70
    group_h = len(engines) * (bar_h + gap) + group_gap
    width = label_w + plot_w + 80
    height = top + len(rows) * group_h + 36
    image = Image.new("RGB", (width * s, height * s), "#ffffff")
    d = ImageDraw.Draw(image)
    regular, bold, big = font(13), font(13, True), font(16, True)
    d.text((16 * s, 14 * s), title, fill="#0f172a", font=big)
    d.text((16 * s, 40 * s), "Character accuracy, 1 - CER, higher is better. Reading order counts.",
           fill="#475569", font=regular)
    for g, (group, picked) in enumerate(rows):
        y0 = top + g * group_h
        mid = y0 + len(engines) * (bar_h + gap) / 2
        d.text((16 * s, (mid - 9) * s), group, fill="#0f172a", font=bold)
        for i, (label, docs) in enumerate(engines):
            acc = accuracy(docs, picked)
            if acc is None:
                continue
            y = y0 + i * (bar_h + gap)
            w = plot_w * acc
            d.rounded_rectangle((label_w * s, y * s, (label_w + w) * s, (y + bar_h) * s),
                                radius=3 * s, fill=COLORS[i % len(COLORS)])
            d.text(((label_w + w + 6) * s, (y + 2) * s), f"{acc * 100:.1f}%", fill="#0f172a", font=regular)
    x = 16
    for i, (label, _) in enumerate(engines):
        d.rectangle((x * s, (height - 26) * s, (x + 12) * s, (height - 14) * s), fill=COLORS[i % len(COLORS)])
        d.text(((x + 18) * s, (height - 28) * s), label, fill="#0f172a", font=regular)
        x += 36 + d.textlength(label, font=regular) / s
    image.save(out, optimize=True)


if __name__ == "__main__":
    main()
