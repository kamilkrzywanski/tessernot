# Cached OCR JSON (bench/out/ro/json) to TSV: eight quad numbers, then the text. Also writes the page text as .txt.
import json, sys, os
src, dst = sys.argv[1], sys.argv[2]
for f in sorted(os.listdir(src)):
    if not f.endswith('.json'): continue
    d = json.load(open(os.path.join(src, f)))
    with open(os.path.join(dst, f[:-5] + '.tsv'), 'w') as out:
        for l in d['lines']:
            t = l['text'].replace('\t', ' ').replace('\n', ' ')
            out.write('\t'.join(repr(float(q)) for q in l['quad']) + '\t' + t + '\n')
    with open(os.path.join(dst, f[:-5] + '.txt'), 'w') as out:
        out.write(d['text'])
