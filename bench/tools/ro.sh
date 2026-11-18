#!/bin/sh
# Reading-order workbench. Run from the repo root after `./mvnw -DskipTests package`.
#   bench/tools/ro.sh dump <ref-dir>...   OCR every reference's image once, with one engine and fragments kept,
#                                         cache lines and their scores in bench/out/ro/tsv; a page already
#                                         cached is skipped, so empty bench/out/ro/json to read all again
#   bench/tools/ro.sh score <ref-dir>...  order the cached lines with the current ReadingOrder source, print CER
#                                         (RO_OPTS=-Drows=true reads row by row, as Layout.ROWS)
#   bench/tools/ro.sh cuts                lines ReadingOrder.cuts would split on the cached pages
#   bench/tools/ro.sh jitter <ref-dir>... order every cached page 40 times with its box edges moved a little,
#                                         as two reads of one page differ: which pages hang on a threshold
#                                         (RO_OPTS=-Dtrials=N)
#   bench/tools/ro.sh show <name>         picture of the current order in bench/out/ro/viz/<name>.png
# The cache holds the engine's lines (text, quads and scores) with the fragments it would drop: score and
# jitter order the lines with them and drop them afterwards, as the engine does (RO_OPTS=-Dkeep=true keeps
# them). Only the reading-order sources are recompiled
# (ReadingOrder and the classes it is split into), so a change to them is scored on every page in
# seconds. Merged-line cuts need the engine: re-dump after changing them.
set -eu
T=bench/tools
W=bench/out/ro
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
J=$JAVA_HOME/bin
P=tessernot/src/main/java/io/github/kamilkrzywanski/ocr/internal/post
SRC="$P/ReadingOrder.java $P/PageSkew.java $P/LineBoxes.java $P/LineRows.java $P/ColumnGutter.java $P/ColumnTests.java $P/ColumnSection.java $P/MergedLines.java $P/Fragments.java"
mkdir -p "$W/json" "$W/tsv" "$W/classes" "$W/viz"
compile() {
  rm -rf "$W/classes" && mkdir -p "$W/classes"
  # shellcheck disable=SC2086
  "$J/javac" -nowarn -d "$W/classes" -cp tessernot/target/classes $SRC "$T/RoBench.java" "$T/RoOrder.java" "$T/RoCuts.java" "$T/RoJitter.java"
}
cmd=$1
shift
case $cmd in
  dump)
    "$J/javac" -nowarn -d "$W/classes" -cp tessernot-cli/target/tessernot-cli.jar "$T/RoDump.java"
    "$J/java" --add-modules jdk.incubator.vector -cp "tessernot-cli/target/tessernot-cli.jar:$W/classes" RoDump "$W/json" "$@"
    python3 -I "$T/totsv.py" "$W/json" "$W/tsv" ;;
  score) compile; "$J/java" -cp "$W/classes:tessernot/target/classes" ${RO_OPTS:-} RoBench "$W/tsv" "$@" ;;
  cuts) compile; "$J/java" -cp "$W/classes:tessernot/target/classes" RoCuts "$W/tsv" ;;
  jitter) compile; "$J/java" -cp "$W/classes:tessernot/target/classes" ${RO_OPTS:-} RoJitter "$W/tsv" "$@" ;;
  show)
    compile
    "$J/java" -cp "$W/classes:tessernot/target/classes" RoOrder "$W/tsv/$1.tsv" "$W/viz/$1.order.tsv"
    for ext in jpg jpeg png tif; do img=bench/data/$1.$ext; [ -f "$img" ] && break; done
    python3 -I "$T/viz.py" "$img" "$W/viz/$1.order.tsv" "$W/viz/$1.png" 1600
    echo "$W/viz/$1.png" ;;
  *) echo "usage: ro.sh dump|score|cuts|jitter|show" >&2; exit 2 ;;
esac
