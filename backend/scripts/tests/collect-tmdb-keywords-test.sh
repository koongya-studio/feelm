#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
TARGET_SCRIPT="$SCRIPT_DIR/../collect-tmdb-keywords.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/out"
printf '2\n3\n' > "$TEST_ROOT/ids.txt"
: > "$TEST_ROOT/request-log"

cat > "$TEST_ROOT/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

output=""
config=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      config="$2"
      shift 2
      ;;
    --output)
      output="$2"
      shift 2
      ;;
    --write-out)
      shift 2
      ;;
    --connect-timeout|--max-time)
      shift 2
      ;;
    --silent|--show-error|--location)
      shift
      ;;
    *)
      shift
      ;;
  esac
done

config_body="$(cat "$config")"
tmdb_id="$(printf '%s\n' "$config_body" | sed -n 's#.*movie/\([0-9][0-9]*\)/keywords.*#\1#p')"
printf '%s\n' "$tmdb_id" >> "$FAKE_REQUEST_LOG"

if [[ "$tmdb_id" == "2" ]]; then
  printf '{"id":2,"keywords":[{"id":11,"name":"alpha"}]}' > "$output"
else
  printf '{"id":3,"keywords":[]}' > "$output"
fi
printf '200'
EOF
chmod +x "$TEST_ROOT/bin/curl"

export PATH="$TEST_ROOT/bin:$PATH"
export FAKE_REQUEST_LOG="$TEST_ROOT/request-log"
export TMDB_TOKEN="test-token"

bash "$TARGET_SCRIPT" \
  --input "$TEST_ROOT/ids.txt" \
  --output-dir "$TEST_ROOT/out" \
  --scope test \
  --workers 2

jq -e '.status == "COMPLETE" and .collection.downloaded == 2 and .collection.reused == 0 and .collection.failed == 0' \
  "$TEST_ROOT/out/keywords-collection-manifest.json" >/dev/null
jq -e '.id == 2 and .keywords[0].name == "alpha"' "$TEST_ROOT/out/raw-keywords/2.json" >/dev/null
jq -e '.id == 3 and (.keywords | length) == 0' "$TEST_ROOT/out/raw-keywords/3.json" >/dev/null
[[ "$(wc -l < "$TEST_ROOT/out/keywords-checkpoint-success.txt" | tr -d ' ')" == "2" ]]
[[ "$(wc -l < "$TEST_ROOT/out/keywords-files.sha256" | tr -d ' ')" == "2" ]]
[[ "$(wc -l < "$TEST_ROOT/request-log" | tr -d ' ')" == "2" ]]

bash "$TARGET_SCRIPT" \
  --input "$TEST_ROOT/ids.txt" \
  --output-dir "$TEST_ROOT/out" \
  --scope test \
  --workers 2

jq -e '.status == "COMPLETE" and .collection.downloaded == 0 and .collection.reused == 2 and .collection.failed == 0' \
  "$TEST_ROOT/out/keywords-collection-manifest.json" >/dev/null
[[ "$(wc -l < "$TEST_ROOT/request-log" | tr -d ' ')" == "2" ]]

# A valid but old file must be replaced, including when workers run in child shells.
printf '{"id":2,"keywords":[{"id":11,"name":"old"}]}' > "$TEST_ROOT/out/raw-keywords/2.json"
bash "$TARGET_SCRIPT" \
  --input "$TEST_ROOT/ids.txt" --output-dir "$TEST_ROOT/out" --scope test --workers 2 --refresh \
  > "$TEST_ROOT/refresh.log" 2>&1
jq -e '.status == "COMPLETE" and .collection.downloaded == 2 and .collection.reused == 0 and .collection.failed == 0' \
  "$TEST_ROOT/out/keywords-collection-manifest.json" >/dev/null
jq -e '.keywords[0].name == "alpha"' "$TEST_ROOT/out/raw-keywords/2.json" >/dev/null
[[ "$(wc -l < "$TEST_ROOT/request-log" | tr -d ' ')" == "4" ]]
if grep -RFq -- "$TMDB_TOKEN" "$TEST_ROOT/out" "$TEST_ROOT/refresh.log" "$TEST_ROOT/request-log"; then
  printf 'token appeared in collection artifacts or logs\n' >&2
  exit 1
fi

printf 'collect-tmdb-keywords-test: PASS\n'
