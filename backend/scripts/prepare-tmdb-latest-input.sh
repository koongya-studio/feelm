#!/usr/bin/env bash
set -uo pipefail

readonly TMDB_API_BASE="${TMDB_API_BASE:-https://api.themoviedb.org/3}"

usage() {
  cat <<'USAGE'
Usage:
  TMDB_TOKEN=... prepare-tmdb-latest-input.sh \
    --base-input FILE --from-date YYYY-MM-DD --to-date YYYY-MM-DD \
    --output-dir DIR

Builds a sorted, unique TMDb movie ID input by combining an existing ID list with
TMDb Discover results for the inclusive primary-release-date range. Discover is
split by day to stay below the 500-page/10,000-result query ceiling. Successful
pages are kept under discover-pages and reused after interruption.

Filters: include_adult=false, include_video=false.
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

for command in awk curl date jq mkdir mktemp mv realpath sha256sum sort stat uniq wc; do
  require_command "$command"
done

[[ -r "$base_input" && -f "$base_input" ]] \
  || fail '--base-input must reference a readable file'
[[ -n "$output_dir" ]] || fail '--output-dir is required'
for value in "$from_date" "$to_date"; do
  [[ "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail 'dates must use YYYY-MM-DD'
  [[ "$(date -d "$value" +%F 2>/dev/null)" == "$value" ]] || fail "invalid date: $value"
done
[[ "$from_date" < "$to_date" || "$from_date" == "$to_date" ]] \
  || fail '--from-date must not be later than --to-date'
[[ -n "${TMDB_TOKEN:-}" ]] || fail 'TMDB_TOKEN environment variable is required'
[[ "$TMDB_TOKEN" =~ ^[A-Za-z0-9._-]+$ ]] \
  || fail 'TMDB_TOKEN contains unsupported characters'

base_input="$(realpath "$base_input")" || fail 'cannot resolve base input path'
mkdir -p "$output_dir/discover-pages" || fail 'cannot create output directory'
output_dir="$(realpath "$output_dir")" || fail 'cannot resolve output directory'
pages_dir="$output_dir/discover-pages"
work_dir="$(mktemp -d "$output_dir/.prepare-latest.XXXXXX")" \
  || fail 'cannot create temporary directory'
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

base_ids="$work_dir/base-ids.txt"
awk '
  { sub(/^\xef\xbb\xbf/, "", $0) }
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*$/ { next }
  { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0); print }
' "$base_input" >"$base_ids"
[[ -s "$base_ids" ]] || fail 'base input contains no TMDb IDs'
if ! awk '/^[1-9][0-9]*$/ && $0 <= 2147483647 { next } { exit 1 }' "$base_ids"; then
  fail 'base input contains an invalid TMDb ID'
fi
duplicate_id="$(sort -n "$base_ids" | uniq -d | awk 'NR == 1 {print}')"
[[ -z "$duplicate_id" ]] || fail "base input contains duplicate TMDb ID: $duplicate_id"
sort -nu "$base_ids" -o "$base_ids"

tmdb_discover() {
  local release_date="$1" page="$2" destination="$3" attempt=0 status retry_after delay
  local header_file="$work_dir/headers" body_file="$work_dir/body"
  while :; do
    : >"$header_file"
    : >"$body_file"
    status="$({ printf 'header = "Authorization: Bearer %s"\n' "$TMDB_TOKEN"; } \
      | curl --config - --silent --show-error --location --get \
          --connect-timeout 10 --max-time 30 \
          --dump-header "$header_file" --output "$body_file" \
          --write-out '%{http_code}' \
          --data-urlencode 'include_adult=false' \
          --data-urlencode 'include_video=false' \
          --data-urlencode 'sort_by=primary_release_date.asc' \
          --data-urlencode "primary_release_date.gte=$release_date" \
          --data-urlencode "primary_release_date.lte=$release_date" \
          --data-urlencode "page=$page" \
          "$TMDB_API_BASE/discover/movie")" || status='000'
    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      mv -f -- "$body_file" "$destination"
      sleep 0.05
      return 0
    fi
    if [[ "$status" != '429' && ! "$status" =~ ^5[0-9][0-9]$ && "$status" != '000' ]]; then
      printf '%s' "$status"
      return 1
    fi
    if [[ "$attempt" -ge 4 ]]; then
      printf '%s' "$status"
      return 1
    fi
    retry_after="$(awk 'BEGIN {IGNORECASE=1} /^Retry-After:/ {gsub("\\r", "", $2); print $2; exit}' "$header_file")"
    if [[ "$retry_after" =~ ^[0-9]+$ ]]; then
      delay="$retry_after"
    else
      delay=$((1 << attempt))
      [[ "$delay" -le 30 ]] || delay=30
    fi
    sleep "$delay"
    attempt=$((attempt + 1))
  done
}

valid_page() {
  local file="$1" expected_page="$2"
  [[ -s "$file" ]] \
    && jq -e --argjson expected "$expected_page" '
      .page == $expected
      and (.total_pages | type) == "number"
      and (.total_results | type) == "number"
      and (.results | type) == "array"
      and all(.results[]; (.id | type) == "number" and .id > 0)
    ' "$file" >/dev/null 2>&1
}

recent_ids="$work_dir/recent-ids.raw"
: >"$recent_ids"
current_date="$from_date"
days=0
pages_downloaded=0
pages_reused=0
while [[ "$current_date" < "$to_date" || "$current_date" == "$to_date" ]]; do
  day_dir="$pages_dir/$current_date"
  mkdir -p "$day_dir" || fail "cannot create discover page directory for $current_date"
  first_page="$day_dir/page-1.json"
  if valid_page "$first_page" 1; then
    pages_reused=$((pages_reused + 1))
  else
    status=''
    status="$(tmdb_discover "$current_date" 1 "$first_page.tmp")" \
      || fail "TMDb Discover failed for $current_date page 1: HTTP $status"
    valid_page "$first_page.tmp" 1 || fail "invalid Discover response for $current_date page 1"
    mv -f -- "$first_page.tmp" "$first_page"
    pages_downloaded=$((pages_downloaded + 1))
  fi
  total_pages="$(jq -r '.total_pages' "$first_page")"
  total_results="$(jq -r '.total_results' "$first_page")"
  ((total_pages <= 500 && total_results <= 10000)) \
    || fail "Discover result for $current_date exceeds the 500-page limit"
  page=1
  while ((page <= total_pages)); do
    page_file="$day_dir/page-$page.json"
    if ((page > 1)); then
      if valid_page "$page_file" "$page"; then
        pages_reused=$((pages_reused + 1))
      else
        status=''
        status="$(tmdb_discover "$current_date" "$page" "$page_file.tmp")" \
          || fail "TMDb Discover failed for $current_date page $page: HTTP $status"
        valid_page "$page_file.tmp" "$page" \
          || fail "invalid Discover response for $current_date page $page"
        mv -f -- "$page_file.tmp" "$page_file"
        pages_downloaded=$((pages_downloaded + 1))
      fi
    fi
    jq -r '.results[].id' "$page_file" >>"$recent_ids"
    page=$((page + 1))
  done
  days=$((days + 1))
  if ((days % 30 == 0)) || [[ "$current_date" == "$to_date" ]]; then
    printf 'discover progress: date=%s days=%d pages_downloaded=%d pages_reused=%d\n' \
      "$current_date" "$days" "$pages_downloaded" "$pages_reused"
  fi
  current_date="$(date -d "$current_date + 1 day" +%F)"
done

sort -nu "$recent_ids" >"$work_dir/recent-ids.txt"
sort -nu "$base_ids" "$work_dir/recent-ids.txt" >"$work_dir/tmdb-ids.txt"
base_count="$(wc -l <"$base_ids" | tr -d '[:space:]')"
recent_count="$(wc -l <"$work_dir/recent-ids.txt" | tr -d '[:space:]')"
final_count="$(wc -l <"$work_dir/tmdb-ids.txt" | tr -d '[:space:]')"
overlap=$((base_count + recent_count - final_count))

{
  printf 'tmdb_id,in_base,in_latest_range\n'
  awk '
    FILENAME == ARGV[1] { base[$1] = 1; next }
    FILENAME == ARGV[2] { recent[$1] = 1; next }
    { printf "%s,%s,%s\n", $1, ($1 in base ? "true" : "false"), ($1 in recent ? "true" : "false") }
  ' "$base_ids" "$work_dir/recent-ids.txt" "$work_dir/tmdb-ids.txt"
} >"$work_dir/tmdb-id-sources.csv"

base_bytes="$(stat -c '%s' "$base_input")"
base_sha="$(sha256sum "$base_input" | awk '{print $1}')"
ids_sha="$(sha256sum "$work_dir/tmdb-ids.txt" | awk '{print $1}')"
sources_sha="$(sha256sum "$work_dir/tmdb-id-sources.csv" | awk '{print $1}')"

cat >"$work_dir/tmdb-input-manifest.json" <<JSON
{
  "status": "PREPARED_NOT_COLLECTED",
  "generated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "dataset": "tmdb",
  "scope": {
    "base": "movielens-ml-32m",
    "primary_release_date_gte": "$from_date",
    "primary_release_date_lte": "$to_date",
    "include_adult": false,
    "include_video": false,
    "deduplicate_key": "tmdb_id"
  },
  "base_input": {
    "file_name": "$(json_escape "$(basename "$base_input")")",
    "bytes": $base_bytes,
    "sha256": "$base_sha",
    "tmdb_ids": $base_count
  },
  "discover": {
    "days": $days,
    "pages_downloaded_this_run": $pages_downloaded,
    "pages_reused_this_run": $pages_reused,
    "unique_tmdb_ids": $recent_count
  },
  "counts": {
    "base_tmdb_ids": $base_count,
    "latest_range_tmdb_ids": $recent_count,
    "overlap_tmdb_ids": $overlap,
    "final_unique_tmdb_ids": $final_count
  },
  "outputs": {
    "tmdb_ids": "tmdb-ids.txt",
    "tmdb_ids_sha256": "$ids_sha",
    "source_map": "tmdb-id-sources.csv",
    "source_map_sha256": "$sources_sha",
    "discover_pages": "discover-pages"
  }
}
JSON

for name in tmdb-ids.txt tmdb-id-sources.csv tmdb-input-manifest.json; do
  mv -f -- "$work_dir/$name" "$output_dir/$name"
done

printf 'Prepared combined TMDb input: base=%d latest=%d overlap=%d final=%d\n' \
  "$base_count" "$recent_count" "$overlap" "$final_count"
