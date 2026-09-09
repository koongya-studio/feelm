#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
subject="$script_dir/../prepare-tmdb-latest-input.sh"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT

mkdir -p "$test_dir/fake-bin"
cat >"$test_dir/fake-bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
header_file=''
output_file=''
release_date=''
page=''
while (($#)); do
  case "$1" in
    --config|--connect-timeout|--max-time|--dump-header|--output|--write-out|--data-urlencode)
      option="$1"
      value="$2"
      [[ "$option" == '--dump-header' ]] && header_file="$value"
      [[ "$option" == '--output' ]] && output_file="$value"
      [[ "$value" == primary_release_date.gte=* ]] && release_date="${value#*=}"
      [[ "$value" == page=* ]] && page="${value#*=}"
      shift 2
      ;;
    --silent|--show-error|--location|--get) shift ;;
    *) shift ;;
  esac
done
printf '%s %s\n' "$release_date" "$page" >>"$CURL_LOG"
: >"$header_file"
case "$release_date:$page" in
  2026-01-01:1) printf '{"page":1,"total_pages":2,"total_results":3,"results":[{"id":10},{"id":20}]}' >"$output_file" ;;
  2026-01-01:2) printf '{"page":2,"total_pages":2,"total_results":3,"results":[{"id":30}]}' >"$output_file" ;;
  2026-01-02:1) printf '{"page":1,"total_pages":1,"total_results":2,"results":[{"id":20},{"id":40}]}' >"$output_file" ;;
  *) exit 9 ;;
esac
printf '200'
SH
chmod +x "$test_dir/fake-bin/curl"

printf '2\n10\n' >"$test_dir/base.txt"
: >"$test_dir/curl.log"
PATH="$test_dir/fake-bin:$PATH" \
TMDB_TOKEN='test-token' CURL_LOG="$test_dir/curl.log" \
TMDB_API_BASE='https://example.invalid/3' \
bash "$subject" \
  --base-input "$test_dir/base.txt" \
  --from-date 2026-01-01 \
  --to-date 2026-01-02 \
  --output-dir "$test_dir/output" >/dev/null

diff -u <(printf '2\n10\n20\n30\n40\n') "$test_dir/output/tmdb-ids.txt"
jq -e '
  .status == "PREPARED_NOT_COLLECTED"
  and .scope.primary_release_date_gte == "2026-01-01"
  and .scope.primary_release_date_lte == "2026-01-02"
  and .counts.base_tmdb_ids == 2
  and .counts.latest_range_tmdb_ids == 4
  and .counts.overlap_tmdb_ids == 1
  and .counts.final_unique_tmdb_ids == 5
' "$test_dir/output/tmdb-input-manifest.json" >/dev/null
[[ "$(wc -l <"$test_dir/curl.log")" -eq 3 ]]

# Persistent Discover pages make the ID preparation resumable too.
PATH="$test_dir/fake-bin:$PATH" \
TMDB_TOKEN='test-token' CURL_LOG="$test_dir/curl.log" \
TMDB_API_BASE='https://example.invalid/3' \
bash "$subject" \
  --base-input "$test_dir/base.txt" \
  --from-date 2026-01-01 \
  --to-date 2026-01-02 \
  --output-dir "$test_dir/output" >/dev/null
[[ "$(wc -l <"$test_dir/curl.log")" -eq 3 ]]
jq -e '.discover.pages_downloaded_this_run == 0 and .discover.pages_reused_this_run == 3' \
  "$test_dir/output/tmdb-input-manifest.json" >/dev/null

printf 'TMDb latest input tests passed\n'
