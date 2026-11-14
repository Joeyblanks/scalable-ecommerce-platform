#!/usr/bin/env bash
# Prints a JSON array of the services to build to $GITHUB_OUTPUT (as "services=[...]").
#
# A service is a top-level directory containing both pom.xml and Dockerfile.
# Everything is built when: the workflow/scripts change, the event is a manual run, or the
# range to compare is unknown (first push, force-push that dropped the base). Otherwise only
# services with changed files are built; docs-only changes build nothing.
#
# Inputs (environment): EVENT, PR_BASE_SHA, PUSH_BEFORE, HEAD_SHA, GITHUB_OUTPUT (optional locally).
set -euo pipefail

mapfile -t all < <(for d in */; do d="${d%/}"; [ -f "$d/pom.xml" ] && [ -f "$d/Dockerfile" ] && echo "$d"; done | sort)

build_all=false
case "${EVENT:-}" in
  pull_request) range="${PR_BASE_SHA}...${HEAD_SHA}" ; base="${PR_BASE_SHA:-}" ;;
  push)         range="${PUSH_BEFORE}..${HEAD_SHA}"  ; base="${PUSH_BEFORE:-}" ;;
  *)            build_all=true ; base="" ; range="" ;;
esac

if [ "$build_all" = false ]; then
  if [ -z "$base" ] || [ "$base" = "0000000000000000000000000000000000000000" ] \
     || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "Unknown comparison base; building all services"
    build_all=true
  fi
fi

selected=()
if [ "$build_all" = true ]; then
  selected=("${all[@]}")
else
  changed="$(git diff --name-only "$range")"
  echo "Changed files in $range:"; echo "$changed" | sed 's/^/  /'
  if echo "$changed" | grep -qE '^\.github/'; then
    echo "CI configuration changed; building all services"
    selected=("${all[@]}")
  else
    for s in "${all[@]}"; do
      if echo "$changed" | grep -q "^${s}/"; then selected+=("$s"); fi
    done
  fi
fi

if [ "${#selected[@]}" -eq 0 ]; then
  json='[]'
else
  json="$(printf '%s\n' "${selected[@]}" | jq -R . | jq -sc .)"
fi
echo "Services to build: $json"
echo "services=$json" >> "${GITHUB_OUTPUT:-/dev/stdout}"
