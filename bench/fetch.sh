#!/bin/sh
# Downloads the held-out images listed in bench/manifest.tsv into bench/data and checks SHA-256.
# A file that already has the right digest is not downloaded again.
set -eu
BENCH=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
DATA="$BENCH/data"
mkdir -p "$DATA"
UA='ocr-pro-bench/1.0 (https://github.com/kamilkrzywanski/ocr-pro)'

digest() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

tail -n +2 "$BENCH/manifest.tsv" | while IFS="$(printf '\t')" read -r name file url sha license author source lang kind notes; do
  [ -n "$name" ] || continue
  dest="$DATA/$file"
  if [ -f "$dest" ] && [ "$(digest "$dest")" = "$sha" ]; then
    continue
  fi
  echo "fetch $file" >&2
  curl -fsSL -A "$UA" -o "$dest.part" "$url"
  got=$(digest "$dest.part")
  if [ "$got" != "$sha" ]; then
    echo "SHA-256 mismatch for $file: $got" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
  sleep 1
done
echo "$DATA"
