#!/bin/sh
# Restores the benchmark images listed in bench/manifest.tsv into bench/data. The images are in
# the submodule bench/data (git submodule update --init bench/data), so this is needed only to
# check them against the sources or to rebuild one.
# Empty fields are written as "-" (read would merge two tabs). A row without a page number is an
# image: it is downloaded and checked against SHA-256. A row with a page number is a document: the
# original is downloaded and checked, then that page is rendered to PNG.
#   PDF:  pdftoppm (poppler-utils) at 200 dpi, or at the dpi given as "page@dpi".
#   DjVu: ddjvu (DjVuLibre) at full resolution, then pnmtopng (netpbm), ImageMagick or Pillow.
# An image with the right digest, or a rendered page that is already there, is kept.
set -eu
BENCH=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
DATA="$BENCH/data"
mkdir -p "$DATA"
UA='tessernot-bench/1.0 (https://github.com/kamilkrzywanski/tessernot)'

digest() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

download() {
  echo "fetch $2" >&2
  curl -fsSL -A "$UA" -o "$2.part" "$1"
  got=$(digest "$2.part")
  if [ "$got" != "$3" ]; then
    echo "SHA-256 mismatch for $2: $got" >&2
    rm -f "$2.part"
    exit 1
  fi
  mv "$2.part" "$2"
  sleep 1
}

# PNM to an RGB PNG, with whichever converter is installed.
topng() {
  if command -v pnmtopng >/dev/null 2>&1; then
    pnmtopng "$1" > "$2"
  elif command -v magick >/dev/null 2>&1; then
    magick "$1" "PNG24:$2"
  elif command -v convert >/dev/null 2>&1; then
    convert "$1" "PNG24:$2"
  elif python3 -I -c 'import PIL' 2>/dev/null; then
    python3 -I -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).save(sys.argv[2])' "$1" "$2"
  else
    echo "pnmtopng (netpbm), ImageMagick or Python Pillow is needed to write $2" >&2
    exit 1
  fi
}

tail -n +2 "$BENCH/manifest.tsv" | while IFS="$(printf '\t')" read -r name set file url sha page rest; do
  [ -n "$name" ] || continue
  dest="$DATA/$file"
  if [ "$page" = "-" ]; then
    if [ -f "$dest" ] && [ "$(digest "$dest")" = "$sha" ]; then
      continue
    fi
    download "$url" "$dest" "$sha"
  else
    if [ -f "$dest" ]; then
      continue
    fi
    case ${url%%\?*} in
      *.djvu|*.DJVU) ext=djvu ;;
      *) ext=pdf ;;
    esac
    src="$DATA/$name.$ext"
    if [ ! -f "$src" ] || [ "$(digest "$src")" != "$sha" ]; then
      download "$url" "$src" "$sha"
    fi
    echo "render $file" >&2
    number=${page%@*}
    if [ "$ext" = djvu ]; then
      command -v ddjvu >/dev/null 2>&1 || { echo "ddjvu (djvulibre) is needed for $name" >&2; exit 1; }
      ddjvu -format=pnm -page="$number" "$src" "$dest.pnm"
      topng "$dest.pnm" "$dest"
      rm -f "$dest.pnm"
    else
      command -v pdftoppm >/dev/null 2>&1 || { echo "pdftoppm (poppler-utils) is needed for $name" >&2; exit 1; }
      dpi=200
      case $page in *@*) dpi=${page#*@} ;; esac
      pdftoppm -r "$dpi" -f "$number" -l "$number" -png -singlefile "$src" "${dest%.png}"
    fi
  fi
done
echo "$DATA"
