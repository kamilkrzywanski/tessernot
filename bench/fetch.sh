#!/bin/sh
# Downloads the benchmark images listed in bench/manifest.tsv into bench/data and checks SHA-256.
# Empty fields are written as "-" (read would merge two tabs). A row with a page number is a
# document: the original is checked, then that page is rendered.
#   PDF:  pdftoppm (poppler-utils) at 200 dpi, or at the dpi given as "page@dpi", to PNG.
#   DjVu: ddjvu (DjVuLibre) at full resolution, to TIFF.
# A file that is already there is not downloaded again.
set -eu
BENCH=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
DATA="$BENCH/data"
mkdir -p "$DATA"
UA='ocr-pro-bench/1.0 (https://github.com/kamilkrzywanski/ocr-pro)'

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

tail -n +2 "$BENCH/manifest.tsv" | while IFS="$(printf '\t')" read -r name set file url sha page rest; do
  [ -n "$name" ] || continue
  dest="$DATA/$file"
  if [ "$page" = "-" ]; then
    if [ -f "$dest" ] && [ "$(digest "$dest")" = "$sha" ]; then
      continue
    fi
    download "$url" "$dest" "$sha"
  else
    case $url in
      *.djvu|*.DJVU) ext=djvu ;;
      *) ext=pdf ;;
    esac
    src="$DATA/$name.$ext"
    if [ ! -f "$src" ] || [ "$(digest "$src")" != "$sha" ]; then
      download "$url" "$src" "$sha"
      rm -f "$dest"
    fi
    if [ ! -f "$dest" ]; then
      number=${page%@*}
      if [ "$ext" = djvu ]; then
        command -v ddjvu >/dev/null 2>&1 || { echo "ddjvu (djvulibre) is needed for $name" >&2; exit 1; }
        ddjvu -format=tiff -page="$number" "$src" "$dest"
      else
        command -v pdftoppm >/dev/null 2>&1 || { echo "pdftoppm (poppler-utils) is needed for $name" >&2; exit 1; }
        dpi=200
        case $page in *@*) dpi=${page#*@} ;; esac
        pdftoppm -r "$dpi" -f "$number" -l "$number" -png -singlefile "$src" "${dest%.png}"
      fi
    fi
  fi
done
echo "$DATA"
