#!/usr/bin/env bash
# Ramp concurrency inside a 4GB/4CPU container until OOM (exit 137) or collapse.
# Usage: bench/ramp.sh <arm> <turns> <ttft> <tokens> <tok-ms> <C1> <C2> ...
set -u
ARM="$1"; TURNS="$2"; TTFT="$3"; TOKENS="$4"; TOKMS="$5"; shift 5
echo "# arm=$ARM turns=$TURNS ttft=$TTFT tokens=$TOKENS tok-ms=$TOKMS  (mem=4g cpus=4)"
for C in "$@"; do
  out=$(timeout 600 docker run --rm --memory=4g --memory-swap=4g --cpus=4 \
          escapement-scale "$ARM" "$C" "$TURNS" "$TTFT" "$TOKENS" "$TOKMS" 2>&1)
  code=$?
  line=$(printf '%s\n' "$out" | grep '^RESULT' | sed 's/^RESULT //')
  if [ "$code" = "137" ]; then
    echo "C=$C  OOM-KILLED (exit 137)"; break
  elif [ -z "$line" ]; then
    echo "C=$C  FAILED (exit $code): $(printf '%s' "$out" | grep -iE 'OutOfMemory|cannot create|unable to create|Error' | head -1)"; break
  else
    echo "C=$C  $line"
  fi
done
