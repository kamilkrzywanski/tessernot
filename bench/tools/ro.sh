#!/bin/sh
# Reading-order workbench. Run from the repo root after `mvn -DskipTests package`.
#   bench/tools/ro.sh dump <ref-dir>...   OCR every reference's image once, cache lines in bench/out/ro/tsv
#   bench/tools/ro.sh score <ref-dir>...  order the cached lines with the current ReadingOrder source, print CER
#                                         (RO_OPTS=-Drows=true reads row by row, as Layout.ROWS)
#   bench/tools/ro.sh cuts                lines ReadingOrder.cuts would split on the cached pages
#   bench/tools/ro.sh show <name>         picture of the current order in bench/out/ro/viz/<name>.png
# The cache holds the engine's lines (text and quads). Only ReadingOrder.java is recompiled, so a change
# to it is scored on every page in seconds. Merged-line cuts need the engine: re-dump after changing them.
set -eu
T=bench/tools
W=bench/out/ro
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
J=$JAVA_HOME/bin
SRC=src/main/java/io/github/kamilkrzywanski/ocr/internal/post/ReadingOrder.java
mkdir -p "$W/json" "$W/tsv" "$W/classes" "$W/viz"
compile() {
  rm -rf "$W/classes" && mkdir -p "$W/classes"
  "$J/javac" -nowarn -d "$W/classes" -cp target/classes "$SRC" "$T/RoBench.java" "$T/RoOrder.java" "$T/RoCuts.java"
}
cmd=$1
shift
case $cmd in
  dump)
    for dir in "$@"; do
      for ref in "$dir"/*.txt; do
        name=$(basename "$ref" .txt)
        [ -s "$W/json/$name.json" ] && continue
        for ext in jpg jpeg png tif tiff; do img=bench/data/$name.$ext; [ -f "$img" ] && break; done
        ./ocr.sh ocr "$img" --json > "$W/json/$name.json" 2>/dev/null || echo "fail $name" >&2
      done
    done
    python3 -I "$T/totsv.py" "$W/json" "$W/tsv" ;;
  score) compile; "$J/java" -cp "$W/classes:target/classes" ${RO_OPTS:-} RoBench "$W/tsv" "$@" ;;
  cuts) compile; "$J/java" -cp "$W/classes:target/classes" RoCuts "$W/tsv" ;;
  show)
    compile
    "$J/java" -cp "$W/classes:target/classes" RoOrder "$W/tsv/$1.tsv" "$W/viz/$1.order.tsv"
    for ext in jpg jpeg png tif; do img=bench/data/$1.$ext; [ -f "$img" ] && break; done
    python3 -I "$T/viz.py" "$img" "$W/viz/$1.order.tsv" "$W/viz/$1.png" 1600
    echo "$W/viz/$1.png" ;;
  *) echo "usage: ro.sh dump|score|cuts|show" >&2; exit 2 ;;
esac
