# Cached OCR JSON (bench/out/ro/json) to TSV: eight quad numbers, then the text. Also writes the
# scores of the lines as .score, one a line in the order of the TSV, and the page text as .txt.
import json, sys, os
src, dst = sys.argv[1], sys.argv[2]
for f in sorted(os.listdir(src)):
    if not f.endswith('.json'): continue
    d = json.load(open(os.path.join(src, f)))
    with open(os.path.join(dst, f[:-5] + '.tsv'), 'w') as out:
        for l in d['lines']:
            t = l['text'].replace('\t', ' ').replace('\n', ' ')
            out.write('\t'.join(repr(float(q)) for q in l['quad']) + '\t' + t + '\n')
    with open(os.path.join(dst, f[:-5] + '.score'), 'w') as out:
        for l in d['lines']:
            # A score that was not finite is null in the JSON; NaN keeps it out of the fragments, as in the engine.
            out.write(('NaN' if l['score'] is None else repr(float(l['score']))) + '\n')
    with open(os.path.join(dst, f[:-5] + '.txt'), 'w') as out:
        out.write(d['text'])
