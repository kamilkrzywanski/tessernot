#!/usr/bin/env python3
"""Markdown tables from `ocr score` outputs, for docs/benchmark.md.

usage: report.py <label>=<score.tsv>[:<times.tsv>] ...

The score file is what `ocr score` prints (per document, then per group). A times file holds
`name<TAB>N ms` lines for engines scored with `--hyp`. The first engine sets the document list.
Prints a summary table, then one row per document with CER / word F1 for each engine.
"""
import statistics
import sys


def read_score(path):
    docs, groups = {}, {}
    in_groups = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if cols[0] == "group":
                in_groups = True
                continue
            if cols[0] == "name" or len(cols) < 4:
                continue
            if in_groups:
                groups[cols[0]] = cols
            else:
                ms = None if len(cols) < 5 or cols[4] in ("-", "") else float(cols[4])
                docs[cols[0]] = {"chars": int(cols[1]), "cer": float(cols[2]), "f1": float(cols[3]), "ms": ms}
    return docs, groups


def read_times(path):
    times = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 2 and cols[1].endswith(" ms"):
                times[cols[0]] = float(cols[1][:-3])
    return times


def main():
    engines = []
    for arg in sys.argv[1:]:
        label, rest = arg.split("=", 1)
        score, _, times = rest.partition(":")
        docs, groups = read_score(score)
        if times:
            for name, ms in read_times(times).items():
                if name in docs:
                    docs[name]["ms"] = ms
        engines.append((label, docs, groups))
    names = sorted(engines[0][1])

    def pooled(docs, prefix):
        picked = [docs[n] for n in names if n in docs and n.startswith(prefix)]
        chars = sum(d["chars"] for d in picked)
        return None if not chars else sum(d["cer"] * d["chars"] for d in picked) / chars

    def f1(groups, key):
        return float(groups[key][4]) if key in groups else None

    def fmt(v, digits=3):
        return "" if v is None else f"{v:.{digits}f}"

    print("| Engine | CER pooled (pl / en / all) | CER median | Word F1 pooled (pl / en / all) | Median page time |")
    print("| --- | --- | --- | --- | --- |")
    for label, docs, groups in engines:
        cers = [docs[n]["cer"] for n in names if n in docs]
        times = [docs[n]["ms"] for n in names if n in docs and docs[n]["ms"] is not None]
        time = f"{statistics.median(times) / 1000:.1f} s" if times else ""
        print(f"| {label} | {fmt(pooled(docs, 'pl-'))} / {fmt(pooled(docs, 'en-'))} / {fmt(pooled(docs, ''))} "
              f"| {fmt(statistics.median(cers))} | {fmt(f1(groups, 'pl'))} / {fmt(f1(groups, 'en'))} / "
              f"{fmt(f1(groups, 'all'))} | {time} |")
    print()
    print("| Document | " + " | ".join(label for label, _, _ in engines) + " |")
    print("| --- |" + " --- |" * len(engines))
    for n in names:
        cells = []
        for _, docs, _ in engines:
            d = docs.get(n)
            cells.append("" if d is None else f"{d['cer']:.3f} / {d['f1']:.3f}")
        print(f"| {n} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
