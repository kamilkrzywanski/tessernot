#!/bin/sh
# Pure Java OCR. The Vector API is optional; without the incubator module the engine uses the scalar backend.
# The caller's working directory is kept, so a relative image or PDF path is resolved from there.
# When --model is omitted on a command that loads weights, models are the directory next to this script.
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd) || exit 1
JAR="$ROOT/ocr-pro-cli/target/ocr-pro-cli.jar"
if [ ! -f "$JAR" ]; then
  echo "missing $JAR — run: mvn -DskipTests package" >&2
  exit 1
fi

has_model=0
cmd=
skip=0
for arg in "$@"; do
  if [ "$skip" -eq 1 ]; then
    skip=0
    continue
  fi
  case $arg in
    --model=*) has_model=1 ;;
    --model) has_model=1; skip=1 ;;
    --limit|--threads|--dpi|--corrections|--layout|--alphabet|--words|--hyp|--out) skip=1 ;;
    --*) ;;
    *)
      if [ -z "$cmd" ]; then
        cmd=$arg
      fi
      ;;
  esac
done

if [ "$has_model" -eq 0 ]; then
  case $cmd in
    ocr|bench|pdf|eval|score|download)
      set -- "$@" --model "$ROOT/models"
      ;;
  esac
fi
# JAVA_HOME wins over the java on PATH, which may be an older release.
JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi
exec "$JAVA" --add-modules jdk.incubator.vector -jar "$JAR" "$@"
