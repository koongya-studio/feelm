#!/usr/bin/env bash

set -uo pipefail

usage() {
  cat <<'EOF'
Usage:
  collect-tmdb-keywords.sh \
    --input FILE \
    --output-dir DIR \
    [--scope SCOPE] \
    [--limit N] \
    [--workers N] \
    [--refresh]

Required environment:
  TMDB_TOKEN  TMDb API Read Access Token

The script stores the unmodified response from /movie/{tmdb_id}/keywords in
raw-keywords/. It uses separate checkpoints and manifests, so it can run next
to collect-tmdb-raw.sh without changing details/credits/providers artifacts.
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

normalize_path() {
  local path="$1"
  if [[ "$path" =~ ^[A-Za-z]:[\\/].* ]] && command -v wslpath >/dev/null 2>&1; then
    wslpath -u "$path"
  else
    printf '%s\n' "$path"
  fi
}

is_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

path_safe_scope() {
  local value="$1"
  [[ "$value" =~ ^[A-Za-z0-9._:+-]+$ ]]
}

INPUT_FILE=""
OUTPUT_DIR=""
SCOPE="tmdb-keywords"
LIMIT=""
WORKERS="1"
REFRESH=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      [[ $# -ge 2 ]] || die "--input requires a value"
      INPUT_FILE="$2"
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || die "--output-dir requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --scope)
      [[ $# -ge 2 ]] || die "--scope requires a value"
      SCOPE="$2"
      shift 2
      ;;
    --limit)
      [[ $# -ge 2 ]] || die "--limit requires a value"
      LIMIT="$2"
      shift 2
      ;;
    --workers)
      [[ $# -ge 2 ]] || die "--workers requires a value"
      WORKERS="$2"
      shift 2
      ;;
    --refresh)
      REFRESH=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$INPUT_FILE" ]] || die "--input is required"
[[ -n "$OUTPUT_DIR" ]] || die "--output-dir is required"
[[ -n "${TMDB_TOKEN:-}" ]] || die "TMDB_TOKEN is not set"
[[ -z "$LIMIT" ]] || is_positive_integer "$LIMIT" || die "--limit must be a positive integer"
is_positive_integer "$WORKERS" || die "--workers must be a positive integer"
(( WORKERS <= 16 )) || die "--workers must be 16 or less"
path_safe_scope "$SCOPE" || die "--scope contains unsupported characters"

for command_name in curl jq sha256sum awk sort wc mktemp date xargs tr sed sleep; do
  require_command "$command_name"
done

INPUT_FILE="$(normalize_path "$INPUT_FILE")"
OUTPUT_DIR="$(normalize_path "$OUTPUT_DIR")"
[[ -f "$INPUT_FILE" ]] || die "input file not found: $INPUT_FILE"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd -P)"
RAW_DIR="$OUTPUT_DIR/raw-keywords"
CHECKPOINT_FILE="$OUTPUT_DIR/keywords-checkpoint-success.txt"
HASH_FILE="$OUTPUT_DIR/keywords-files.sha256"
FAILURE_REPORT_FILE="$OUTPUT_DIR/keywords-failure-report.json"
MANIFEST_FILE="$OUTPUT_DIR/keywords-collection-manifest.json"
SUMMARY_FILE="$OUTPUT_DIR/keywords-collection-summary.txt"
mkdir -p "$RAW_DIR"

WORK_DIR="$(mktemp -d "$OUTPUT_DIR/.collect-tmdb-keywords.XXXXXX")"
cleanup() {
  local target="${WORK_DIR:-}"
  if [[ -n "$target" && "$target" == "$OUTPUT_DIR"/.collect-tmdb-keywords.* && -d "$target" ]]; then
    rm -rf -- "$target"
  fi
}
trap cleanup EXIT

IDS_FILE="$WORK_DIR/ids.txt"
RESULT_DIR="$WORK_DIR/results"
mkdir -p "$RESULT_DIR"

awk 'NF { print $1 }' "$INPUT_FILE" > "$WORK_DIR/input-values.txt"
if grep -Ev '^[1-9][0-9]*$' "$WORK_DIR/input-values.txt" > "$WORK_DIR/invalid-values.txt"; then
  die "input contains invalid TMDb ids; first invalid value: $(head -n 1 "$WORK_DIR/invalid-values.txt")"
fi

sort -n -u "$WORK_DIR/input-values.txt" > "$IDS_FILE"
if [[ -n "$LIMIT" ]]; then
  head -n "$LIMIT" "$IDS_FILE" > "$WORK_DIR/limited-ids.txt"
  mv "$WORK_DIR/limited-ids.txt" "$IDS_FILE"
fi

INPUT_COUNT="$(wc -l < "$IDS_FILE" | tr -d ' ')"
(( INPUT_COUNT > 0 )) || die "input contains no TMDb ids"
INPUT_SHA256="$(sha256sum "$IDS_FILE" | awk '{print $1}')"
STARTED_AT="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

valid_keyword_json() {
  local file="$1"
  local expected_id="$2"
  [[ -s "$file" ]] || return 1
  jq -e --argjson expected "$expected_id" '
    (.id == $expected)
    and (.keywords | type == "array")
    and all(.keywords[];
      (type == "object")
      and (.id | type == "number")
      and (.id > 0)
      and (.name | type == "string")
      and (.name | length > 0)
    )
  ' "$file" >/dev/null 2>&1
}

collect_one() {
  local tmdb_id="$1"
  local destination="$RAW_DIR/$tmdb_id.json"
  local result_file="$RESULT_DIR/$tmdb_id.tsv"
  local temp_body="$WORK_DIR/$tmdb_id.body"
  local response_code=""
  local attempt=1
  local max_attempts=8
  local retry_after=""
  local curl_exit=0

  if (( REFRESH == 0 )) && valid_keyword_json "$destination" "$tmdb_id"; then
    printf '%s\tREUSED\t\n' "$tmdb_id" > "$result_file"
    return 0
  fi

  while (( attempt <= max_attempts )); do
    : > "$temp_body"
    response_code="$({
      printf 'header = "Authorization: Bearer %s"\n' "$TMDB_TOKEN"
      printf 'header = "accept: application/json"\n'
      printf 'url = "https://api.themoviedb.org/3/movie/%s/keywords"\n' "$tmdb_id"
    } | curl --config - --silent --show-error --location \
      --connect-timeout 10 --max-time 45 \
      --output "$temp_body" --write-out '%{http_code}' 2>/dev/null)" || curl_exit=$?

    if (( curl_exit != 0 )); then
      response_code="000"
    fi

    if [[ "$response_code" == "200" ]]; then
      if valid_keyword_json "$temp_body" "$tmdb_id"; then
        mv "$temp_body" "$destination"
        printf '%s\tDOWNLOADED\t\n' "$tmdb_id" > "$result_file"
        return 0
      fi
      printf '%s\tFAILED\tINVALID_TMDB_KEYWORDS_JSON\n' "$tmdb_id" > "$result_file"
      return 0
    fi

    if [[ "$response_code" == "429" || "$response_code" == "000" || "$response_code" =~ ^5[0-9][0-9]$ ]]; then
      if (( attempt < max_attempts )); then
        retry_after="$(jq -r '.retry_after // empty' "$temp_body" 2>/dev/null || true)"
        if ! [[ "$retry_after" =~ ^[0-9]+$ ]]; then
          retry_after=$(( attempt * 2 ))
        fi
        (( retry_after > 30 )) && retry_after=30
        sleep "$retry_after"
        attempt=$(( attempt + 1 ))
        curl_exit=0
        continue
      fi
    fi

    printf '%s\tFAILED\tTMDB_KEYWORDS_HTTP_%s\n' "$tmdb_id" "$response_code" > "$result_file"
    return 0
  done
}

export TMDB_TOKEN RAW_DIR RESULT_DIR WORK_DIR REFRESH
export -f valid_keyword_json collect_one

xargs -r -n 1 -P "$WORKERS" bash -c 'collect_one "$1"' _ < "$IDS_FILE" &
XARGS_PID=$!

while kill -0 "$XARGS_PID" 2>/dev/null; do
  COMPLETED="$(find "$RESULT_DIR" -maxdepth 1 -type f -name '*.tsv' | wc -l | tr -d ' ')"
  printf 'keywords_progress=%s/%s workers=%s\n' "$COMPLETED" "$INPUT_COUNT" "$WORKERS"
  sleep 5
done

wait "$XARGS_PID"

RESULTS_FILE="$WORK_DIR/results.tsv"
find "$RESULT_DIR" -maxdepth 1 -type f -name '*.tsv' -print0 \
  | sort -z \
  | xargs -0 -r cat > "$RESULTS_FILE"

RESULT_COUNT="$(wc -l < "$RESULTS_FILE" | tr -d ' ')"
[[ "$RESULT_COUNT" == "$INPUT_COUNT" ]] || die "worker result count mismatch: expected=$INPUT_COUNT actual=$RESULT_COUNT"

awk -F '\t' '$2 == "DOWNLOADED" || $2 == "REUSED" { print $1 }' "$RESULTS_FILE" \
  | sort -n -u > "$CHECKPOINT_FILE.tmp"
mv "$CHECKPOINT_FILE.tmp" "$CHECKPOINT_FILE"

DOWNLOADED_COUNT="$(awk -F '\t' '$2 == "DOWNLOADED" { count++ } END { print count+0 }' "$RESULTS_FILE")"
REUSED_COUNT="$(awk -F '\t' '$2 == "REUSED" { count++ } END { print count+0 }' "$RESULTS_FILE")"
FAILED_COUNT="$(awk -F '\t' '$2 == "FAILED" { count++ } END { print count+0 }' "$RESULTS_FILE")"
SUCCESS_COUNT=$(( DOWNLOADED_COUNT + REUSED_COUNT ))

: > "$HASH_FILE.tmp"
while IFS= read -r tmdb_id; do
  [[ -n "$tmdb_id" ]] || continue
  sha256sum "$RAW_DIR/$tmdb_id.json" | awk -v id="$tmdb_id" '{print $1 "  raw-keywords/" id ".json"}'
done < "$CHECKPOINT_FILE" | sort -k2,2 > "$HASH_FILE.tmp"
mv "$HASH_FILE.tmp" "$HASH_FILE"

FAILURES_JSON="$WORK_DIR/failures.json"
awk -F '\t' '$2 == "FAILED" { print $1 "\t" $3 }' "$RESULTS_FILE" \
  | jq -Rn '[inputs | split("\t") | {tmdb_id: (.[0] | tonumber), reason: .[1]}]' \
  > "$FAILURES_JSON"

jq -n \
  --arg generated_at "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
  --arg scope "$SCOPE" \
  --arg input "$INPUT_FILE" \
  --arg input_sha256 "$INPUT_SHA256" \
  --argjson input_count "$INPUT_COUNT" \
  --argjson failed "$FAILED_COUNT" \
  --slurpfile failures "$FAILURES_JSON" \
  '{
    generated_at: $generated_at,
    dataset: "tmdb-keywords",
    scope: $scope,
    input: $input,
    input_sha256: $input_sha256,
    input_count: $input_count,
    failed: $failed,
    failures: $failures[0]
  }' > "$FAILURE_REPORT_FILE.tmp"
mv "$FAILURE_REPORT_FILE.tmp" "$FAILURE_REPORT_FILE"

FINISHED_AT="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
STATUS="COMPLETE"
(( FAILED_COUNT == 0 )) || STATUS="PARTIAL"

jq -n \
  --arg generated_at "$FINISHED_AT" \
  --arg started_at "$STARTED_AT" \
  --arg status "$STATUS" \
  --arg scope "$SCOPE" \
  --arg input "$INPUT_FILE" \
  --arg input_sha256 "$INPUT_SHA256" \
  --arg endpoint '/movie/{tmdb_id}/keywords' \
  --arg raw_directory "$RAW_DIR" \
  --arg checkpoint_file "$CHECKPOINT_FILE" \
  --arg hashes_file "$HASH_FILE" \
  --arg failure_report "$FAILURE_REPORT_FILE" \
  --argjson input_count "$INPUT_COUNT" \
  --argjson success "$SUCCESS_COUNT" \
  --argjson downloaded "$DOWNLOADED_COUNT" \
  --argjson reused "$REUSED_COUNT" \
  --argjson failed "$FAILED_COUNT" \
  --argjson workers "$WORKERS" \
  '{
    generated_at: $generated_at,
    started_at: $started_at,
    status: $status,
    dataset: "tmdb-keywords",
    scope: $scope,
    api_endpoint: $endpoint,
    input: {
      path: $input,
      sha256: $input_sha256,
      unique_id_count: $input_count
    },
    collection: {
      success: $success,
      downloaded: $downloaded,
      reused: $reused,
      failed: $failed,
      workers: $workers
    },
    artifacts: {
      raw_directory: $raw_directory,
      checkpoint_file: $checkpoint_file,
      hashes_file: $hashes_file,
      failure_report: $failure_report
    }
  }' > "$MANIFEST_FILE.tmp"
mv "$MANIFEST_FILE.tmp" "$MANIFEST_FILE"

cat > "$SUMMARY_FILE.tmp" <<EOF
status=$STATUS
dataset=tmdb-keywords
scope=$SCOPE
input_count=$INPUT_COUNT
success=$SUCCESS_COUNT
downloaded=$DOWNLOADED_COUNT
reused=$REUSED_COUNT
failed=$FAILED_COUNT
workers=$WORKERS
raw_directory=$RAW_DIR
manifest=$MANIFEST_FILE
EOF
mv "$SUMMARY_FILE.tmp" "$SUMMARY_FILE"

printf 'TMDb keywords: input=%s success=%s downloaded=%s reused=%s failed=%s\n' \
  "$INPUT_COUNT" "$SUCCESS_COUNT" "$DOWNLOADED_COUNT" "$REUSED_COUNT" "$FAILED_COUNT"
printf 'Manifest: %s\n' "$MANIFEST_FILE"

(( FAILED_COUNT == 0 )) || exit 3
