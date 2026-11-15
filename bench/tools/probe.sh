#!/bin/sh
# Robustness probe: every page of dev and test read again after a change that should not matter
# (turned, askew, half the resolution, a strong JPEG) or as a camera changes a page (perspective,
# blur, uneven light), with the edits against the reference for each. Run from the repo root
# after `./mvnw -DskipTests package`.
#   bench/tools/probe.sh bench/out/probe.tsv none rot90 skew3 half persx12 blur15
#   bench/tools/probe.sh bench/out/probe.tsv --only en-book-alice-p88 --dump bench/out/probe-pictures keyy12
# The variants are listed in Probe.java. It scores bench/ref/dev and bench/ref/test only.
set -eu
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
JAR=ocr-pro-cli/target/ocr-pro-cli.jar
W=bench/out/probe-classes
[ -f "$JAR" ] || { echo "missing $JAR: run ./mvnw -DskipTests package" >&2; exit 1; }
mkdir -p "$W"
"$JAVA_HOME/bin/javac" -nowarn -d "$W" -cp "$JAR" bench/tools/Probe.java
out=$1
shift
exec "$JAVA_HOME/bin/java" --add-modules jdk.incubator.vector -cp "$JAR:$W" Probe . "$out" "$@"
