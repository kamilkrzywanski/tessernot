# Draws the boxes of one page in reading order, numbered and joined by a red line.
import sys, os
from PIL import Image, ImageDraw, ImageFont
# viz.py <image> <tsv-in-output-order> <out.png> [max-side]
img = Image.open(sys.argv[1]).convert('RGB')
lines = [l.rstrip('\n').split('\t') for l in open(sys.argv[2])]
d = ImageDraw.Draw(img)
try:
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', max(14, img.height // 120))
except Exception:
    font = ImageFont.load_default()
prev = None
for i, l in enumerate(lines):
    q = [float(v) for v in l[:8]]
    xs, ys = q[0::2], q[1::2]
    box = (min(xs), min(ys), max(xs), max(ys))
    hue = (i * 37) % 255
    color = (255 - hue, hue, 128)
    d.rectangle(box, outline=color, width=3)
    c = ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
    if prev: d.line([prev, c], fill=(255, 0, 0), width=2)
    prev = c
    d.text((box[0], box[1]), str(i), fill=(0, 0, 255), font=font)
m = int(sys.argv[4]) if len(sys.argv) > 4 else 1600
img.thumbnail((m, m))
img.save(sys.argv[3])
