#!/bin/sh
# Baseline: Tesseract with the language of each document (pl-* -> pol, en-* -> eng), default page segmentation.
#   bench/run-tesseract.sh bench/ref bench/data bench/out/tesseract
# Writes <name>.txt per reference and prints the wall time of each call (process start and model load included).
set -eu
REF=$1
DATA=$2
OUT=$3
mkdir -p "$OUT"
for ref in "$REF"/*.txt; do
  name=$(basename "$ref" .txt)
  image=$(ls "$DATA/$name".* 2>/dev/null | head -n 1)
  [ -n "$image" ] || { echo "no image for $name" >&2; exit 1; }
  case $name in
    pl-*) lang=pol ;;
    *) lang=eng ;;
  esac
  start=$(date +%s%N)
  tesseract "$image" "$OUT/$name" -l "$lang" >/dev/null 2>&1
  end=$(date +%s%N)
  printf '%s\t%d ms\n' "$name" $(( (end - start) / 1000000 ))
done
