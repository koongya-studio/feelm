#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
collector="$script_dir/../collect-tmdb-raw.sh"
ingestion="$script_dir/../run-tmdb-ingestion.sh"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT

mkdir -p "$test_dir/fake-bin"
cat >"$test_dir/fake-bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
header_file=''
output_file=''
url=''
while (($#)); do
  case "$1" in
    --config|--connect-timeout|--max-time|--dump-header|--output|--write-out)
      option="$1"
      value="$2"
      [[ "$option" == '--dump-header' ]] && header_file="$value"
      [[ "$option" == '--output' ]] && output_file="$value"
      shift 2
      ;;
    --silent|--show-error|--location) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s\n' "$url" >>"$CURL_LOG"
: >"$header_file"
movie_id="$(printf '%s' "$url" | sed -E 's#.*movie/([0-9]+).*#\1#')"
case "$url" in
  *append_to_response*)
    printf '{"id":%s,"title":"Movie %s","original_title":"Movie %s","genres":[],"production_countries":[],"credits":{"id":%s,"crew":[],"cast":[]},"watch/providers":{"id":%s,"results":{"KR":{"link":"https://example.invalid/watch","flatrate":[]}}}}' \
      "$movie_id" "$movie_id" "$movie_id" "$movie_id" "$movie_id" >"$output_file"
    ;;
  */credits*) printf '{"id":%s,"crew":[],"cast":[]}' "$movie_id" >"$output_file" ;;
  */watch/providers*) printf '{"id":%s,"results":{"KR":{"link":"https://example.invalid/watch","flatrate":[]}}}' "$movie_id" >"$output_file" ;;
  *) printf '{"id":%s,"title":"Movie %s","original_title":"Movie %s","genres":[],"production_countries":[]}' "$movie_id" "$movie_id" "$movie_id" >"$output_file" ;;
esac
jq "${RESPONSE_FILTER:-.}" "$output_file" >"$output_file.filtered"
mv "$output_file.filtered" "$output_file"
printf '200'
SH
chmod +x "$test_dir/fake-bin/curl"

printf '2\n3\n' >"$test_dir/tmdb-ids.txt"
: >"$test_dir/curl.log"
PATH="$test_dir/fake-bin:$PATH" \
TMDB_TOKEN='test-token' CURL_LOG="$test_dir/curl.log" \
TMDB_API_BASE='https://example.invalid/3' \
bash "$collector" \
  --input "$test_dir/tmdb-ids.txt" \
  --output-dir "$test_dir/output" \
  --scope test-scope >/dev/null

jq -e '
  .status == "COMPLETE"
  and .input.tmdb_ids == 2
  and .collection.raw_files == 2
  and .collection.downloaded_this_run == 2
  and .collection.failed_this_run == 0
  and .postgresql.status == "NOT_RUN"
' "$test_dir/output/collection-manifest.json" >/dev/null
[[ "$(wc -l <"$test_dir/curl.log")" -eq 2 ]]
[[ "$(wc -l <"$test_dir/output/raw-files.sha256")" -eq 2 ]]
(
  cd "$test_dir/output"
  sha256sum --check raw-files.sha256 >/dev/null
)

# The second run must reuse valid files without another API request.
PATH="$test_dir/fake-bin:$PATH" \
TMDB_TOKEN='test-token' CURL_LOG="$test_dir/curl.log" \
TMDB_API_BASE='https://example.invalid/3' \
bash "$collector" \
  --input "$test_dir/tmdb-ids.txt" \
  --output-dir "$test_dir/output" \
  --scope test-scope >/dev/null
jq -e '
  .status == "COMPLETE"
  and .collection.downloaded_this_run == 0
  and .collection.reused_this_run == 2
' "$test_dir/output/collection-manifest.json" >/dev/null
[[ "$(wc -l <"$test_dir/curl.log")" -eq 2 ]]

# Collected raw files can later be loaded without a TMDb token or API calls.
env -u TMDB_TOKEN bash "$ingestion" \
  --input "$test_dir/tmdb-ids.txt" \
  --raw-dir "$test_dir/output/raw-tmdb" \
  --output-dir "$test_dir/db-dry-run" \
  --dry-run >/dev/null
jq -e '.postgresql.status == "SUCCESS" and .postgresql.succeeded == 2' \
  "$test_dir/db-dry-run/run-manifest.json" >/dev/null

# Missing sections/fields, wrong types and conflicting IDs must never be published.
printf '2\n' >"$test_dir/one-id.txt"
case_number=0
while IFS= read -r filter; do
  case_number=$((case_number + 1))
  for mode in fresh refresh; do
    output="$test_dir/invalid-$case_number-$mode"
    args=()
    if [[ "$mode" == 'refresh' ]]; then
      mkdir -p "$output/raw-tmdb"
      cp "$test_dir/output/raw-tmdb/2.json" "$output/raw-tmdb/2.json"
      args+=(--refresh)
    fi
    printf 'raw invalid case=%s mode=%s\n' "$case_number" "$mode"
    if PATH="$test_dir/fake-bin:$PATH" TMDB_TOKEN='test-token' \
      CURL_LOG="$test_dir/curl.log" RESPONSE_FILTER="$filter" \
      TMDB_API_BASE='https://example.invalid/3' \
      bash "$collector" --input "$test_dir/one-id.txt" --output-dir "$output" \
        --scope invalid-test "${args[@]}" >"$test_dir/invalid.log" 2>&1; then
      printf 'incomplete raw response was accepted: %s\n' "$filter" >&2
      exit 1
    fi
    jq -e '.status != "COMPLETE" and .collection.failed_this_run == 1
      and .collection.downloaded_this_run == 0 and .collection.reused_this_run == 0' \
      "$output/collection-manifest.json" >/dev/null
    jq -e 'length == 1 and .[0].tmdb_id == 2 and (.[0].reason | length > 0)' \
      "$output/failure-report.json" >/dev/null
    if [[ "$mode" == 'refresh' ]]; then
      cmp "$test_dir/output/raw-tmdb/2.json" "$output/raw-tmdb/2.json"
    else
      [[ ! -e "$output/raw-tmdb/2.json" ]]
      [[ ! -s "$output/collection-checkpoint-success.txt" ]]
    fi
    [[ -z "$(find "$output/raw-tmdb" -name '*.tmp.*' -print)" ]]
  done
done <<'FILTERS'
del(.credits)
del(."watch/providers")
del(.credits.cast)
del(.credits.crew)
del(."watch/providers".results)
del(.genres)
del(.production_countries)
.credits.cast = null
.credits.crew = {}
."watch/providers".results = []
.genres = {}
.production_countries = null
.credits = []
."watch/providers" = null
.id = 99
.credits.id = 99
."watch/providers".id = 99
FILTERS

# Appended sections may omit their redundant ID; valid empty results remain valid.
PATH="$test_dir/fake-bin:$PATH" TMDB_TOKEN='test-token' CURL_LOG="$test_dir/curl.log" \
RESPONSE_FILTER='del(.credits.id, ."watch/providers".id) | ."watch/providers".results = {}' \
TMDB_API_BASE='https://example.invalid/3' \
bash "$collector" --input "$test_dir/one-id.txt" --output-dir "$test_dir/empty" \
  --scope empty-test >/dev/null
jq -e '.credits.id == 2 and .providers.id == 2 and .credits.cast == []
  and .credits.crew == [] and .providers.results == {}' "$test_dir/empty/raw-tmdb/2.json" >/dev/null

printf 'TMDb raw collection tests passed\n'
