#!/usr/bin/env python3
"""Recognize ocr-pro's own line boxes with Tesseract (--psm 7), keep ocr-pro's reading order.

usage: tessline.py <repo> <out-dir> <tessdata-dir|-> <ref-dir>...
Needs the line cache of `ro.sh dump` (bench/out/ro/json), Pillow, and tesseract with the pol and
eng models (`-` uses the installed ones). Writes <out-dir>/tess/<name>.txt and
<out-dir>/ocrpro/<name>.txt for `ocr score --hyp`. Extra tesseract options go in TESS_EXTRA.
"""
import json, math, os, subprocess, sys, tempfile
from multiprocessing import Pool
from PIL import Image, ImageOps

repo, out, tessdata = sys.argv[1], sys.argv[2], sys.argv[3]
refs = sys.argv[4:]
files = {}
for line in open(os.path.join(repo, 'bench/manifest.tsv'), encoding='utf-8'):
    c = line.rstrip('\n').split('\t')
    if len(c) >= 3:
        files[c[0]] = c[2]


def crop(im, q):
    p = [(q[i], q[i + 1]) for i in range(0, 8, 2)]  # TL, TR, BR, BL
    w = int(round(max(math.dist(p[0], p[1]), math.dist(p[3], p[2]))))
    h = int(round(max(math.dist(p[0], p[3]), math.dist(p[1], p[2]))))
    w, h = max(w, 1), max(h, 1)
    c = im.transform((w, h), Image.QUAD, data=(*p[0], *p[3], *p[2], *p[1]), resample=Image.BICUBIC)
    if h >= 1.5 * w:
        c = c.rotate(90, expand=True)
    bg = sorted(c.getdata())[int(0.95 * (c.width * c.height - 1))]
    return ImageOps.expand(c, border=10, fill=bg)


def run(job):
    name, ref_dir = job
    d = json.load(open(os.path.join(repo, 'bench/out/ro/json', name + '.json'), encoding='utf-8'))
    lines = d['lines']
    lang = 'pol' if name.startswith('pl-') else 'eng'
    im = ImageOps.exif_transpose(Image.open(os.path.join(repo, 'bench/data', files[name]))).convert('L')
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for i, l in enumerate(lines):
            p = os.path.join(tmp, f'{i:05d}.png')
            crop(im, l['quad']).save(p)
            paths.append(p)
        with open(os.path.join(tmp, 'list.txt'), 'w') as f:
            f.write('\n'.join(paths) + '\n')
        cmd = ['tesseract', os.path.join(tmp, 'list.txt'), 'stdout', '--psm', '7', '--dpi', '300', '-l', lang]
        cmd += os.environ.get('TESS_EXTRA', '').split()
        if tessdata != '-':
            cmd[1:1] = ['--tessdata-dir', tessdata]
        env = dict(os.environ, OMP_THREAD_LIMIT='1')
        res = subprocess.run(cmd, capture_output=True, text=True, env=env)
        pages = res.stdout.split('\f')
    texts = [t.strip().replace('\n', ' ') for t in pages[:len(lines)]]
    if len(texts) != len(lines):
        print(f'{name}: {len(texts)} texts for {len(lines)} lines', file=sys.stderr)
    for sub, body in (('tess', texts), ('ocrpro', [l['text'] for l in lines])):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
        with open(os.path.join(out, sub, name + '.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(body) + '\n')
    return name


jobs = []
for r in refs:
    for fn in sorted(os.listdir(r)):
        name = fn[:-4]
        if fn.endswith('.txt') and os.path.exists(os.path.join(repo, 'bench/out/ro/json', name + '.json')):
            jobs.append((name, r))
with Pool(8) as pool:
    for n in pool.imap_unordered(run, jobs):
        print('done', n, file=sys.stderr)
