#!/bin/sh
# Downloads an Apache Ranger release file and verifies its SHA-512.
# Tries the CDN mirror first (fast, current releases only), then the permanent archive (slow).
# Usage: fetch-release.sh <path under ranger/> <sha512> <output file>
set -eu
path="$1"; sha="$2"; out="$3"
for base in https://dlcdn.apache.org/ranger https://archive.apache.org/dist/ranger; do
  echo "Downloading ${base}/${path}"
  if curl -fL --retry 3 --connect-timeout 20 -o "$out" "${base}/${path}"; then
    echo "${sha}  ${out}" | sha512sum -c - && exit 0
    echo "Checksum mismatch for ${base}/${path}" >&2
  fi
done
echo "Could not download ${path}" >&2
exit 1
