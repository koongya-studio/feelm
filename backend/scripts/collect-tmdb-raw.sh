#!/usr/bin/env bash
set -uo pipefail

readonly TMDB_API_BASE="${TMDB_API_BASE:-https://api.themoviedb.org/3}"

usage() {
  cat <<'USAGE'
Usage:
  TMDB_TOKEN=... collect-tmdb-raw.sh \
    --input FILE --output-dir DIR --scope SCOPE [options]

Required:
  --input FILE       One positive TMDb movie ID per line
  --output-dir DIR   Persistent directory for raw JSON, checkpoint and manifests
  --scope SCOPE      Version/range label written to the collection manifest

Options:
  --limit N          Process only the first N IDs (default: 0, all)
  --workers N        Concurrent TMDb requests (default: 8, max: 16)
  --refresh          Re-fetch JSON already recorded in the collection checkpoint
  --help             Show this help

The token is read only from TMDB_TOKEN and is never written to output files.
Each successful movie is atomically written and immediately checkpointed. A rerun
validates and reuses completed JSON, so Ctrl+C, network loss and machine restarts
do not discard finished work.
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

input=''
output_dir=''
scope=''
limit='0'
workers='8'
refresh='false'

while (($#)); do
  case "$1" in
    --input) (($# >= 2)) || fail '--input requires a value'; input="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || fail '--output-dir requires a value'; output_dir="$2"; shift 2 ;;
    --scope) (($# >= 2)) || fail '--scope requires a value'; scope="$2"; shift 2 ;;
    --limit) (($# >= 2)) || fail '--limit requires a value'; limit="$2"; shift 2 ;;
    --workers) (($# >= 2)) || fail '--workers requires a value'; workers="$2"; shift 2 ;;
    --refresh) refresh='true'; shift ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

for command in awk cp curl date find grep head jq mktemp mv realpath sha256sum sort stat uniq wc xargs; do
  require_command "$command"
done

[[ -r "$input" && -f "$input" ]] || fail '--input must reference a readable file'
[[ -n "$output_dir" ]] || fail '--output-dir is required'
[[ "$scope" =~ ^[A-Za-z0-9._:+-]+$ ]] || fail '--scope is required and must be path-safe'
[[ "$limit" =~ ^[0-9]+$ ]] || fail '--limit must be zero or a positive integer'
[[ "$workers" =~ ^[1-9][0-9]*$ && "$workers" -le 16 ]] \
  || fail '--workers must be an integer between 1 and 16'
[[ -n "${TMDB_TOKEN:-}" ]] || fail 'TMDB_TOKEN environment variable is required'
[[ "$TMDB_TOKEN" =~ ^[A-Za-z0-9._-]+$ ]] \
  || fail 'TMDB_TOKEN contains unsupported characters'

input="$(realpath "$input")" || fail 'cannot resolve input path'
mkdir -p "$output_dir/raw-tmdb" || fail 'cannot create output directory'
output_dir="$(realpath "$output_dir")" || fail 'cannot resolve output directory'
raw_dir="$output_dir/raw-tmdb"
work_dir="$(mktemp -d "$output_dir/.collect-tmdb.XXXXXX")" \
  || fail 'cannot create temporary directory'
cleanup() {
  local child_pids
  child_pids="$(jobs -pr)"
  if [[ -n "$child_pids" ]]; then
    kill $child_pids >/dev/null 2>&1 || true
    wait $child_pids >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir"
}
abort() { exit 130; }
trap abort INT TERM
trap cleanup EXIT

ids_file="$work_dir/tmdb-ids.txt"
awk '
  { sub(/^\xef\xbb\xbf/, "", $0) }
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*$/ { next }
  { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0); print }
' "$input" >"$ids_file"
[[ -s "$ids_file" ]] || fail 'input contains no TMDb IDs'
if ! awk '/^[1-9][0-9]*$/ && $0 <= 2147483647 { next } { exit 1 }' "$ids_file"; then
  fail 'input contains a non-numeric, non-positive, or out-of-range TMDb ID'
fi
duplicate_id="$(sort -n "$ids_file" | uniq -d | head -n 1)"
[[ -z "$duplicate_id" ]] || fail "input contains duplicate TMDb ID: $duplicate_id"
if [[ "$limit" -gt 0 ]]; then
  head -n "$limit" "$ids_file" >"$work_dir/limited-ids.txt"
  mv "$work_dir/limited-ids.txt" "$ids_file"
fi

input_bytes="$(stat -c '%s' "$input")"
input_sha256="$(sha256sum "$input" | awk '{print $1}')"
input_count="$(wc -l <"$ids_file" | tr -d '[:space:]')"
checkpoint="$output_dir/collection-checkpoint-success.txt"
[[ ! -e "$checkpoint" || -f "$checkpoint" ]] || fail 'checkpoint path is not a regular file'
touch "$checkpoint" || fail 'cannot create collection checkpoint'
if ! awk '/^[1-9][0-9]*$/ {next} /^[[:space:]]*$/ {next} {exit 1}' "$checkpoint"; then
  fail 'collection checkpoint contains an invalid TMDb ID'
fi

valid_raw() {
  local expected_id="$1" file="$2"
  [[ -s "$file" ]] || return 1
  jq -e --argjson expected "$expected_id" '
    (.details | type) == "object"
    and (.credits | type) == "object"
    and (.providers | type) == "object"
    and (.details.id == $expected)
    and (.credits.id == $expected)
    and (.providers.id == $expected)
    and (.details.genres | type) == "array"
    and (.details.production_countries | type) == "array"
    and (.credits.cast | type) == "array"
    and (.credits.crew | type) == "array"
    and (.providers.results | type) == "object"
  ' "$file" >/dev/null 2>&1
}

tmdb_get() {
  local endpoint="$1" destination="$2" request_key="$3"
  local attempt=0 status retry_after delay
  local header_file="$work_dir/$request_key.headers" body_file="$work_dir/$request_key.body"
  while :; do
    : >"$header_file"
    : >"$body_file"
    status="$({ printf 'header = "Authorization: Bearer %s"\n' "$TMDB_TOKEN"; } \
      | curl --config - --silent --show-error --location \
          --connect-timeout 10 --max-time 30 \
          --dump-header "$header_file" --output "$body_file" \
          --write-out '%{http_code}' "$TMDB_API_BASE/$endpoint")" || status='000'
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

status_dir="$work_dir/status"
mkdir -p "$status_dir"
failures_tsv="$work_dir/failures.tsv"
: >"$failures_tsv"

collect_one() {
  local tmdb_id="$1" raw_file combined status raw_tmp
  raw_file="$raw_dir/$tmdb_id.json"
  if [[ "$refresh" == 'false' ]] && valid_raw "$tmdb_id" "$raw_file"; then
    printf '%s\tREUSED\n' "$tmdb_id" >"$status_dir/$tmdb_id.tsv"
    return 0
  else
    combined="$work_dir/$tmdb_id-combined.json"
    status=''
    status="$(tmdb_get \
      "movie/$tmdb_id?language=ko-KR&append_to_response=credits,watch%2Fproviders" \
      "$combined" "$tmdb_id-combined")" \
      || { printf '%s\tFAILED\tTMDB_COMBINED_HTTP_%s\n' "$tmdb_id" "$status" >"$status_dir/$tmdb_id.tsv"; return 0; }

    raw_tmp="$raw_dir/.$tmdb_id.json.tmp.$BASHPID"
    if ! jq '. as $response |
        if (.credits | type) != "object" or (."watch/providers" | type) != "object"
        then error("missing or invalid appended response") else {
          details: ($response | del(.credits, ."watch/providers")),
          # Appended responses can omit their redundant ID. Never replace a supplied ID.
          credits: ($response.credits | if has("id") then . else . + {id:$response.id} end),
          providers: ($response."watch/providers" | if has("id") then . else . + {id:$response.id} end)
        } end' "$combined" >"$raw_tmp" 2>/dev/null \
        || ! valid_raw "$tmdb_id" "$raw_tmp"; then
      rm -f -- "$raw_tmp"
      printf '%s\tFAILED\tINVALID_TMDB_JSON\n' "$tmdb_id" >"$status_dir/$tmdb_id.tsv"
      return 0
    fi
    mv -f -- "$raw_tmp" "$raw_file" \
      || fail "cannot publish raw JSON for TMDb ID $tmdb_id"
    printf '%s\tDOWNLOADED\n' "$tmdb_id" >"$status_dir/$tmdb_id.tsv"
  fi
}

processed=0
batch_size=0
while IFS= read -r tmdb_id; do
  collect_one "$tmdb_id" &
  batch_size=$((batch_size + 1))
  if ((batch_size >= workers)); then
    wait || true
    processed=$((processed + batch_size))
    batch_size=0
    if ((processed % 100 < workers || processed == input_count)); then
      printf 'progress=%d/%d workers=%d\n' "$processed" "$input_count" "$workers"
    fi
  fi
done <"$ids_file"
if ((batch_size > 0)); then
  wait || true
  processed=$((processed + batch_size))
fi
printf 'progress=%d/%d workers=%d\n' "$processed" "$input_count" "$workers"

downloaded=0
reused=0
failed=0
while IFS=$'\t' read -r tmdb_id result reason; do
  case "$result" in
    DOWNLOADED) downloaded=$((downloaded + 1)) ;;
    REUSED) reused=$((reused + 1)) ;;
    FAILED)
      failed=$((failed + 1))
      printf '%s\t%s\n' "$tmdb_id" "$reason" >>"$failures_tsv"
      ;;
  esac
done < <(find "$status_dir" -type f -name '*.tsv' -print0 | sort -z | xargs -0 cat)

# Publish one checkpoint update after all workers finish. Raw files are written
# atomically and remain the source of truth if the process is interrupted.
cp "$checkpoint" "$work_dir/checkpoint.all"
while IFS= read -r tmdb_id; do
  valid_raw "$tmdb_id" "$raw_dir/$tmdb_id.json" \
    && printf '%s\n' "$tmdb_id" >>"$work_dir/checkpoint.all"
done <"$ids_file"
sort -nu "$work_dir/checkpoint.all" >"$work_dir/checkpoint.sorted"
mv -f -- "$work_dir/checkpoint.sorted" "$checkpoint" \
  || fail 'raw JSON files were written but checkpoint could not be updated'

checksum_list_tmp="$work_dir/raw-files.sha256"
: >"$checksum_list_tmp"
raw_count=0
raw_bytes=0
missing=0
while IFS= read -r tmdb_id; do
  raw_file="$raw_dir/$tmdb_id.json"
  if [[ -s "$raw_file" ]]; then
    bytes="$(stat -c '%s' "$raw_file")"
    sha="$(sha256sum "$raw_file" | awk '{print $1}')"
    printf '%s  raw-tmdb/%s.json\n' "$sha" "$tmdb_id" \
      >>"$checksum_list_tmp"
    raw_count=$((raw_count + 1))
    raw_bytes=$((raw_bytes + bytes))
  else
    missing=$((missing + 1))
  fi
done <"$ids_file"
mv -f -- "$checksum_list_tmp" "$output_dir/raw-files.sha256" \
  || fail 'cannot publish raw checksum list'
checksum_list_sha="$(sha256sum "$output_dir/raw-files.sha256" | awk '{print $1}')"

jq -Rn '[inputs | split("\t") | {tmdb_id:(.[0]|tonumber),reason:.[1]}]' \
  <"$failures_tsv" >"$work_dir/failure-report.json"
mv -f -- "$work_dir/failure-report.json" "$output_dir/failure-report.json" \
  || fail 'cannot publish failure report'

if [[ "$raw_count" -eq "$input_count" && "$missing" -eq 0 && "$failed" -eq 0 ]]; then
  collection_status='COMPLETE'
else
  collection_status='PARTIAL'
fi

cat >"$work_dir/collection-manifest.json" <<JSON
{
  "status": "$collection_status",
  "generated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "dataset": "tmdb",
  "scope": "$(json_escape "$scope")",
  "api_language": "ko-KR",
  "provider_region": "KR",
  "provider_offer_type": "flatrate",
  "input": {
    "file_name": "$(json_escape "$(basename "$input")")",
    "bytes": $input_bytes,
    "sha256": "$input_sha256",
    "tmdb_ids": $input_count
  },
  "collection": {
    "raw_files": $raw_count,
    "raw_bytes": $raw_bytes,
    "downloaded_this_run": $downloaded,
    "reused_this_run": $reused,
    "failed_this_run": $failed,
    "missing_files": $missing
  },
  "outputs": {
    "raw_directory": "raw-tmdb",
    "checkpoint": "collection-checkpoint-success.txt",
    "failure_report": "failure-report.json",
    "checksum_list": "raw-files.sha256",
    "checksum_list_sha256": "$checksum_list_sha"
  },
  "postgresql": {"status":"NOT_RUN"},
  "hdfs": {"status":"PREPARED_NOT_UPLOADED"}
}
JSON
mv -f -- "$work_dir/collection-manifest.json" "$output_dir/collection-manifest.json" \
  || fail 'cannot publish collection manifest'

{
  printf 'TMDb collection: status=%s input=%d raw=%d downloaded=%d reused=%d failed=%d missing=%d bytes=%d\n' \
    "$collection_status" "$input_count" "$raw_count" "$downloaded" "$reused" "$failed" "$missing" "$raw_bytes"
  printf 'PostgreSQL: NOT_RUN\n'
  printf 'HDFS: PREPARED_NOT_UPLOADED\n'
} | tee "$output_dir/collection-summary.txt"

[[ "$collection_status" == 'COMPLETE' ]] || exit 3
