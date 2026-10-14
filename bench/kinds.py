"""Document groups of the benchmark, from the manifest's `kind` column. Used by report.py and chart.py."""

GROUPS = [
    ("Invoices, bills, receipts", {"invoice", "receipt"}),
    ("Letters, forms, notices", {"letter", "form", "notice", "other"}),
    ("Books, newspapers, gazettes", {"book", "newspaper", "journal", "gazette", "dictionary", "paper", "magazine"}),
]


def manifest_kinds(path):
    kinds = {}
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        k = header.index("kind")
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) > k:
                kinds[cols[0]] = cols[k]
    return kinds
