#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'USAGE'
Usage:
  TMDB_TOKEN=... prepare-tmdb-raw-handoff.sh \
    --base-input FILE --from-date YYYY-MM-DD --to-date YYYY-MM-DD \
    --output-dir DIR

Runs the resumable latest-ID discovery and raw TMDb collection in sequence.
Rerun the exact same command after interruption; completed pages and movies are
validated and reused. PostgreSQL access is not required.
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 2
}

base_input=''
from_date=''
to_date=''
output_dir=''

while (($#)); do
  case "$1" in
    --base-input) (($# >= 2)) || fail '--base-input requires a value'; base_input="$2"; shift 2 ;;
    --from-date) (($# >= 2)) || fail '--from-date requires a value'; from_date="$2"; shift 2 ;;
    --to-date) (($# >= 2)) || fail '--to-date requires a value'; to_date="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || fail '--output-dir requires a value'; output_dir="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ -n "$base_input" ]] || fail '--base-input is required'
[[ -n "$from_date" ]] || fail '--from-date is required'
[[ -n "$to_date" ]] || fail '--to-date is required'
[[ -n "$output_dir" ]] || fail '--output-dir is required'

bash "$script_dir/prepare-tmdb-latest-input.sh" \
  --base-input "$base_input" \
  --from-date "$from_date" \
  --to-date "$to_date" \
  --output-dir "$output_dir"

bash "$script_dir/collect-tmdb-raw.sh" \
  --input "$output_dir/tmdb-ids.txt" \
  --output-dir "$output_dir" \
  --scope "movielens-ml-32m+tmdb-release-${from_date}_to_${to_date}"

printf 'TMDb handoff data is ready: %s\n' "$output_dir"
