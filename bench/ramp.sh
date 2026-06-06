#!/usr/bin/env bash
# Ramp concurrency inside a constrained container until the process collapses:
# OOM-kill (exit 137), native-thread exhaustion, or a missing RESULT line.
#
# Build an image first (from the checkout you want to measure):
#   docker build -f bench/Dockerfile -t esc-bench:branch .
# then:
#   bench/ramp.sh esc-bench:branch sc-mem 6 200 50 5 1000 2000 4000 8000
#
# Container caps default to 2 CPU / 2 GB (override with CPUS=/MEM= env vars).
# Usage: bench/ramp.sh <image> <arm> <turns> <ttft> <tokens> <tok-ms> <C1> <C2> ...
set -u
IMAGE="$1"; ARM="$2"; TURNS="$3"; TTFT="$4"; TOKENS="$5"; TOKMS="$6"; shift 6
CPUS="${CPUS:-2}"; MEM="${MEM:-2g}"
echo "# image=$IMAGE arm=$ARM turns=$TURNS ttft=$TTFT tokens=$TOKENS tok-ms=$TOKMS  (mem=$MEM cpus=$CPUS)"
for C in "$@"; do
  out=$(timeout 600 docker run --rm --memory="$MEM" --memory-swap="$MEM" --cpus="$CPUS" \
          "$IMAGE" "$ARM" "$C" "$TURNS" "$TTFT" "$TOKENS" "$TOKMS" 2>&1)
  code=$?
  line=$(printf '%s\n' "$out" | grep '^RESULT' | sed 's/^RESULT //')
  if [ "$code" = "137" ]; then
    echo "C=$C  OOM-KILLED (exit 137)"; break
  elif [ -z "$line" ]; then
    echo "C=$C  FAILED (exit $code): $(printf '%s' "$out" | grep -iE 'OutOfMemory|cannot create|unable to create|native thread|Error' | head -1)"; break
  else
    echo "C=$C  $line"
  fi
done
