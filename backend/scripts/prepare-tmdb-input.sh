#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  prepare-tmdb-input.sh \
    --movielens-links FILE \
    [--ssafy-movies FILE] \
    --scope SCOPE \
    [--sample-size N] \
    --output-dir DIR

Builds a sorted, unique TMDb ID input from MovieLens links.csv and an optional
SSAFY pipe-separated movie file whose first row contains an id column.

The script does not call TMDb, PostgreSQL, or HDFS. It writes:
  tmdb-ids.txt
  tmdb-ids-sample.txt
  tmdb-id-sources.csv
  tmdb-input-manifest.json
  sample-expected.json
USAGE
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"
}

json_escape() {
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  printf '%s' "$value"
}

movielens_links=''
ssafy_movies=''
scope=''
sample_size=5
output_dir=''

while (($#)); do
  case "$1" in
    --movielens-links)
      (($# >= 2)) || fail '--movielens-links requires a value'
      movielens_links="$2"
      shift 2
      ;;
    --ssafy-movies)
      (($# >= 2)) || fail '--ssafy-movies requires a value'
      ssafy_movies="$2"
      shift 2
      ;;
    --scope)
      (($# >= 2)) || fail '--scope requires a value'
      scope="$2"
      shift 2
      ;;
    --sample-size)
      (($# >= 2)) || fail '--sample-size requires a value'
      sample_size="$2"
      shift 2
      ;;
    --output-dir)
      (($# >= 2)) || fail '--output-dir requires a value'
      output_dir="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

for command in awk basename date head mktemp mv realpath sha256sum sort stat wc; do
  require_command "$command"
done

[[ -r "$movielens_links" && -f "$movielens_links" ]] \
  || fail '--movielens-links must reference a readable file'
if [[ -n "$ssafy_movies" ]]; then
  [[ -r "$ssafy_movies" && -f "$ssafy_movies" ]] \
    || fail '--ssafy-movies must reference a readable file'
fi
[[ "$scope" =~ ^[A-Za-z0-9._-]+$ ]] \
  || fail '--scope is required and must be path-safe'
[[ "$sample_size" =~ ^[1-9][0-9]*$ ]] \
  || fail '--sample-size must be a positive integer'
((sample_size <= 1000)) || fail '--sample-size must not exceed 1000'
[[ -n "$output_dir" ]] || fail '--output-dir is required'

movielens_links="$(realpath "$movielens_links")"
if [[ -n "$ssafy_movies" ]]; then
  ssafy_movies="$(realpath "$ssafy_movies")"
fi
mkdir -p "$output_dir"
output_dir="$(realpath "$output_dir")"
work_dir="$(mktemp -d "$output_dir/.prepare-tmdb-input.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

ml_ids_raw="$work_dir/movielens-ids.raw"
ml_stats="$work_dir/movielens.stats"
: >"$ml_ids_raw"
if ! awk -F',' -v ids="$ml_ids_raw" -v stats="$ml_stats" '
  NR == 1 {
    sub(/^\xef\xbb\xbf/, "", $1)
    for (i = 1; i <= NF; i++) {
      gsub(/\r$/, "", $i)
      if ($i == "tmdbId") column = i
    }
    if (!column) {
      print "MovieLens header must contain tmdbId" > "/dev/stderr"
      exit 2
    }
    next
  }
  {
    rows++
    value = $column
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    gsub(/\r$/, "", value)
    if (value == "") {
      missing++
      next
    }
    if (value !~ /^[0-9]+$/ || value + 0 < 1 || value + 0 > 2147483647) {
      printf "invalid MovieLens tmdbId at row %d: %s\n", NR, value > "/dev/stderr"
      invalid++
      next
    }
    print value > ids
    present++
  }
  END {
    printf "%d %d %d %d\n", rows + 0, present + 0, missing + 0, invalid + 0 > stats
    if (invalid > 0) exit 2
  }
' "$movielens_links"; then
  fail 'MovieLens links.csv validation failed'
fi

read -r movielens_rows movielens_present movielens_missing _ <"$ml_stats"
sort -n -u "$ml_ids_raw" >"$work_dir/movielens-ids.txt"
movielens_unique="$(wc -l <"$work_dir/movielens-ids.txt" | tr -d '[:space:]')"
movielens_duplicates=$((movielens_present - movielens_unique))

: >"$work_dir/ssafy-ids.txt"
ssafy_rows=0
ssafy_present=0
ssafy_missing=0
ssafy_unique=0
ssafy_duplicates=0
if [[ -n "$ssafy_movies" ]]; then
  ssafy_ids_raw="$work_dir/ssafy-ids.raw"
  ssafy_stats="$work_dir/ssafy.stats"
  : >"$ssafy_ids_raw"
  if ! awk -F'|' -v ids="$ssafy_ids_raw" -v stats="$ssafy_stats" '
    NR == 1 {
      sub(/^\xef\xbb\xbf/, "", $1)
      for (i = 1; i <= NF; i++) {
        gsub(/\r$/, "", $i)
        if ($i == "id") column = i
      }
      if (!column) {
        print "SSAFY header must contain id" > "/dev/stderr"
        exit 2
      }
      next
    }
    {
      rows++
      value = $column
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/\r$/, "", value)
      if (value == "") {
        missing++
        next
      }
      if (value !~ /^[0-9]+$/ || value + 0 < 1 || value + 0 > 2147483647) {
        printf "invalid SSAFY movie id at row %d: %s\n", NR, value > "/dev/stderr"
        invalid++
        next
      }
      print value > ids
      present++
    }
    END {
      printf "%d %d %d %d\n", rows + 0, present + 0, missing + 0, invalid + 0 > stats
      if (invalid > 0) exit 2
    }
  ' "$ssafy_movies"; then
    fail 'SSAFY movie file validation failed'
  fi
  read -r ssafy_rows ssafy_present ssafy_missing _ <"$ssafy_stats"
  sort -n -u "$ssafy_ids_raw" >"$work_dir/ssafy-ids.txt"
  ssafy_unique="$(wc -l <"$work_dir/ssafy-ids.txt" | tr -d '[:space:]')"
  ssafy_duplicates=$((ssafy_present - ssafy_unique))
fi

sort -n -u "$work_dir/movielens-ids.txt" "$work_dir/ssafy-ids.txt" \
  >"$work_dir/tmdb-ids.txt"
final_unique="$(wc -l <"$work_dir/tmdb-ids.txt" | tr -d '[:space:]')"
((final_unique > 0)) || fail 'no valid TMDb IDs were found'
source_overlap=$((movielens_unique + ssafy_unique - final_unique))

sample_candidates="$work_dir/sample-candidates.txt"
: >"$sample_candidates"
movielens_sample_size=$(((sample_size + 1) / 2))
head -n "$movielens_sample_size" "$work_dir/movielens-ids.txt" >>"$sample_candidates"

awk '
  FILENAME == ARGV[1] { movielens[$1] = 1; next }
  !($1 in movielens) { print }
' "$work_dir/movielens-ids.txt" "$work_dir/ssafy-ids.txt" >"$work_dir/ssafy-only-ids.txt"
selected_count="$(wc -l <"$sample_candidates" | tr -d '[:space:]')"
remaining=$((sample_size - selected_count))
if ((remaining > 0)); then
  head -n "$remaining" "$work_dir/ssafy-only-ids.txt" >>"$sample_candidates"
fi

sort -n -u "$sample_candidates" >"$work_dir/sample-selected.txt"
selected_count="$(wc -l <"$work_dir/sample-selected.txt" | tr -d '[:space:]')"
if ((selected_count < sample_size)); then
  awk '
    FILENAME == ARGV[1] { selected[$1] = 1; next }
    !($1 in selected) { print }
  ' "$work_dir/sample-selected.txt" "$work_dir/tmdb-ids.txt" >"$work_dir/sample-fill.txt"
  remaining=$((sample_size - selected_count))
  head -n "$remaining" "$work_dir/sample-fill.txt" >>"$work_dir/sample-selected.txt"
fi
sort -n -u "$work_dir/sample-selected.txt" >"$work_dir/tmdb-ids-sample.txt"
sample_count="$(wc -l <"$work_dir/tmdb-ids-sample.txt" | tr -d '[:space:]')"

{
  printf 'tmdb_id,in_movielens,in_ssafy\n'
  awk '
    FILENAME == ARGV[1] { movielens[$1] = 1; next }
    FILENAME == ARGV[2] { ssafy[$1] = 1; next }
    { printf "%s,%s,%s\n", $1, ($1 in movielens ? "true" : "false"), ($1 in ssafy ? "true" : "false") }
  ' "$work_dir/movielens-ids.txt" "$work_dir/ssafy-ids.txt" "$work_dir/tmdb-ids.txt"
} >"$work_dir/tmdb-id-sources.csv"

movielens_bytes="$(stat -c '%s' "$movielens_links")"
movielens_sha="$(sha256sum "$movielens_links" | awk '{print $1}')"
ssafy_json='null'
if [[ -n "$ssafy_movies" ]]; then
  ssafy_bytes="$(stat -c '%s' "$ssafy_movies")"
  ssafy_sha="$(sha256sum "$ssafy_movies" | awk '{print $1}')"
  ssafy_json="{\"name\":\"$(json_escape "$(basename "$ssafy_movies")")\",\"bytes\":$ssafy_bytes,\"sha256\":\"$ssafy_sha\"}"
fi
tmdb_ids_sha="$(sha256sum "$work_dir/tmdb-ids.txt" | awk '{print $1}')"
sample_sha="$(sha256sum "$work_dir/tmdb-ids-sample.txt" | awk '{print $1}')"
sources_sha="$(sha256sum "$work_dir/tmdb-id-sources.csv" | awk '{print $1}')"

cat >"$work_dir/tmdb-input-manifest.json" <<JSON
{
  "status": "PREPARED_NOT_INGESTED",
  "generated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "scope": "$(json_escape "$scope")",
  "sources": {
    "movielens_links": {"name":"$(json_escape "$(basename "$movielens_links")")","bytes":$movielens_bytes,"sha256":"$movielens_sha"},
    "ssafy_movies": $ssafy_json
  },
  "counts": {
    "movielens_rows": $movielens_rows,
    "movielens_with_tmdb_id": $movielens_present,
    "movielens_missing_tmdb_id": $movielens_missing,
    "movielens_unique_tmdb_ids": $movielens_unique,
    "movielens_duplicate_tmdb_rows": $movielens_duplicates,
    "ssafy_rows": $ssafy_rows,
    "ssafy_with_tmdb_id": $ssafy_present,
    "ssafy_missing_tmdb_id": $ssafy_missing,
    "ssafy_unique_tmdb_ids": $ssafy_unique,
    "ssafy_duplicate_tmdb_rows": $ssafy_duplicates,
    "source_overlap_tmdb_ids": $source_overlap,
    "final_unique_tmdb_ids": $final_unique,
    "sample_tmdb_ids": $sample_count
  },
  "outputs": {
    "tmdb_ids": "tmdb-ids.txt",
    "tmdb_ids_sha256": "$tmdb_ids_sha",
    "sample": "tmdb-ids-sample.txt",
    "sample_sha256": "$sample_sha",
    "source_map": "tmdb-id-sources.csv",
    "source_map_sha256": "$sources_sha"
  }
}
JSON

cat >"$work_dir/sample-expected.json" <<JSON
{
  "input": "tmdb-ids-sample.txt",
  "expected": {
    "input_ids": $sample_count,
    "postgresql_status": "SUCCESS",
    "failed": 0,
    "rerun_skips": $sample_count,
    "service_movie_csv_header": "service_movie_id,tmdb_id",
    "hdfs_status": "NOT_RUN_BY_POSTGRES_SCRIPT"
  }
}
JSON

for name in tmdb-ids.txt tmdb-ids-sample.txt tmdb-id-sources.csv \
  tmdb-input-manifest.json sample-expected.json; do
  mv -f -- "$work_dir/$name" "$output_dir/$name"
done

printf 'Prepared TMDb input: ids=%d sample=%d MovieLens=%d SSAFY=%d overlap=%d\n' \
  "$final_unique" "$sample_count" "$movielens_unique" "$ssafy_unique" "$source_overlap"
