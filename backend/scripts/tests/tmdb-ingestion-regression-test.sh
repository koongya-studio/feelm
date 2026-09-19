#!/usr/bin/env bash
set -euo pipefail

# Real SQL regression tests. Requires Docker and jq, like the backend integration tests.
# A new disposable database has no network, published ports or host mounts.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
subject="$script_dir/../run-tmdb-ingestion.sh"
test_dir="$(mktemp -d)"
container_id=''
docker_command='docker'
if ! docker version >/dev/null 2>&1; then
  docker_command='docker.exe'
  "$docker_command" version >/dev/null
fi
cleanup() {
  if [[ -n "$container_id" ]]; then
    "$docker_command" rm -f "$container_id" >/dev/null
  fi
  rm -rf -- "$test_dir"
}
trap cleanup EXIT
container_id="$("$docker_command" run -d --rm --network none \
  -e POSTGRES_HOST_AUTH_METHOD=trust pgvector/pgvector:0.8.6-pg17-bookworm)"
container_id="${container_id//$'\r'/}"
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if "$docker_command" exec "$container_id" pg_isready -U postgres >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]]
db() { "$docker_command" exec -i "$container_id" psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres "$@"; }
cat "$script_dir"/../../src/main/resources/db/migration/*.sql | db -q >/dev/null

mkdir -p "$test_dir/bin" "$test_dir/raw"
export TEST_DOCKER_COMMAND="$docker_command" TEST_POSTGRES_CONTAINER="$container_id"
export SQL_LOG="$test_dir/sql.log" RESPONSE_FILE="$test_dir/response.json" CURL_LOG="$test_dir/curl.log"
: >"$SQL_LOG"
: >"$CURL_LOG"
export PGHOST='unused.invalid' PGPORT=5432 PGDATABASE=postgres PGUSER=postgres
export PGPASSWORD='test-only' TMDB_TOKEN='test-token' TMDB_API_BASE='https://example.invalid/3'
export PATH="$test_dir/bin:$PATH"

# The wrapper executes every statement on the isolated DB and can inject export failures.
cat >"$test_dir/bin/psql" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
sql=''
command_sql=false
args=("$@")
while (($#)); do
  if [[ "$1" == '-c' ]]; then sql="$2"; command_sql=true; break; fi
  shift
done
if [[ "$command_sql" == false ]]; then sql="$(cat)"; fi
printf '%s\n' "$sql" >>"$SQL_LOG"
if [[ "$sql" == 'COPY (SELECT id AS service_movie_id'* ]]; then
  case "${EXPORT_MODE:-normal}" in
    query) exit 1 ;;
    header) printf 'wrong,header\n1,2\n'; exit 0 ;;
    duplicate) printf 'service_movie_id,tmdb_id\n1,2\n1,3\n'; exit 0 ;;
  esac
fi
if [[ "${EXPORT_MODE:-normal}" == sample && "$sql" == COPY* && "$sql" == *genre_count* ]]; then exit 1; fi
if [[ "$command_sql" == true ]]; then
  exec "$TEST_DOCKER_COMMAND" exec -i "$TEST_POSTGRES_CONTAINER" psql -X -U postgres -d postgres "${args[@]}"
fi
printf '%s\n' "$sql" | "$TEST_DOCKER_COMMAND" exec -i "$TEST_POSTGRES_CONTAINER" \
  psql -X -U postgres -d postgres "${args[@]}"
SH
cat >"$test_dir/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output='' header='' url=''
while (($#)); do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    --dump-header) header="$2"; shift 2 ;;
    --config|--connect-timeout|--max-time|--write-out) shift 2 ;;
    --silent|--show-error|--location) shift ;;
    *) url="$1"; shift ;;
  esac
done
cat >/dev/null
printf '%s\n' "$url" >>"$CURL_LOG"
: >"$header"
case "$url" in
  */credits*) jq '.credits' "$RESPONSE_FILE" >"$output" ;;
  */watch/providers*) jq '.providers' "$RESPONSE_FILE" >"$output" ;;
  *) jq '.details' "$RESPONSE_FILE" >"$output" ;;
esac
printf '200'
SH
chmod +x "$test_dir/bin/psql" "$test_dir/bin/curl"
cat >"$test_dir/good.json" <<'JSON'
{
  "details":{"id":2,"title":"Sample","genres":[{"id":18,"name":"Drama"},{"id":18,"name":"Drama"}],"production_countries":[{"iso_3166_1":"KR"}]},
  "credits":{"id":2,"cast":[{"id":20,"name":"Person","order":0}],"crew":[{"id":20,"name":"Person","job":"Director"}]},
  "providers":{"id":2,"results":{"KR":{"link":"https://example.invalid/watch","flatrate":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":337,"provider_name":"Disney Plus"}],"rent":[{"provider_id":97}],"buy":[{"provider_id":356}],"free":[{"provider_id":1883}],"ads":[{"provider_id":97}]},"US":{"flatrate":[{"provider_id":97}]}}}
}
JSON
cp "$test_dir/good.json" "$test_dir/raw/2.json"
printf '2\n' >"$test_dir/ids.txt"
printf 'tmdb_provider_id,code,name,logo_path\n8,NETFLIX,Netflix,/netflix.jpg\n' >"$test_dir/approved.csv"
run_ingestion() {
  bash "$subject" --input "$test_dir/ids.txt" --output-dir "$1" --postgres-mode direct "${@:2}" </dev/null
}
expect_failure() {
  local status=0
  "$@" >"$test_dir/failure.log" 2>&1 || status=$?
  if [[ "$status" == 0 ]]; then
    printf 'expected failure: %s\n' "$*" >&2
    exit 1
  fi
}
snapshot() {
  db -qAt <<'SQL'
SELECT json_build_array(
  (SELECT json_agg(t ORDER BY id) FROM movies t),
  (SELECT json_agg(t ORDER BY movie_id,genre_id) FROM movie_genres t),
  (SELECT json_agg(t ORDER BY movie_id,country_code) FROM movie_countries t),
  (SELECT json_agg(t ORDER BY movie_id,person_id,role) FROM movie_people t),
  (SELECT json_agg(t ORDER BY movie_id,ott_id) FROM movie_ott_providers t));
SQL
}
group="${1:-all}"

if [[ "$group" == all || "$group" == titles ]]; then
  printf 'ingestion regression: Korean display title and original title\n'
  printf '2\n' >"$test_dir/ids.txt"
  jq '.details.title = "Foreign Title" | .details.original_title = "Original Title"
    | .details.translations = {translations:[{iso_639_1:"ko",iso_3166_1:"KR",data:{title:"한국 번역 제목"}}]}
    | .details.alternative_titles = {titles:[]}' \
    "$test_dir/good.json" >"$test_dir/title-translation.json"
  cp "$test_dir/title-translation.json" "$test_dir/raw/2.json"
  run_ingestion "$test_dir/title-translation" --raw-dir "$test_dir/raw" >/dev/null
  [[ "$(db -qAt -c "SELECT title || '|' || original_title FROM movies WHERE tmdb_id=2")" == '한국 번역 제목|Original Title' ]]

  jq 'del(.details.translations)
    | .details.alternative_titles = {titles:[{iso_3166_1:"KR",title:"한국 대체 제목"}]}' \
    "$test_dir/title-translation.json" >"$test_dir/raw/2.json"
  run_ingestion "$test_dir/title-alternative" --raw-dir "$test_dir/raw" >/dev/null
  [[ "$(db -qAt -c 'SELECT title FROM movies WHERE tmdb_id=2')" == '한국 대체 제목' ]]

  jq '.details.alternative_titles.titles = [
      {iso_3166_1:"KR",title:"첫 번째 제목"},
      {iso_3166_1:"KR",title:"두 번째 제목"}]' \
    "$test_dir/raw/2.json" >"$test_dir/title-ambiguous.json"
  cp "$test_dir/title-ambiguous.json" "$test_dir/raw/2.json"
  run_ingestion "$test_dir/title-ambiguous" --raw-dir "$test_dir/raw" >/dev/null
  [[ "$(db -qAt -c "SELECT title || '|' || original_title FROM movies WHERE tmdb_id=2")" == '한국 대체 제목|Original Title' ]]

  db -q -c "UPDATE movies SET title='Foreign Title' WHERE tmdb_id=2" >/dev/null
  run_ingestion "$test_dir/title-ambiguous" --raw-dir "$test_dir/raw" --refresh >/dev/null
  [[ "$(db -qAt -c 'SELECT title FROM movies WHERE tmdb_id=2')" == 'Foreign Title' ]]

  cp "$test_dir/title-translation.json" "$RESPONSE_FILE"
  run_ingestion "$test_dir/title-api" >/dev/null
  [[ "$(db -qAt -c 'SELECT title FROM movies WHERE tmdb_id=2')" == '한국 번역 제목' ]]
  grep -q 'append_to_response=translations,alternative_titles' "$CURL_LOG"
fi

if [[ "$group" == all || "$group" == ott ]]; then
  printf 'ingestion regression: approved OTT SQL and repeat\n'
  [[ "$(db -qAt -c "SELECT count(*) FROM otts WHERE (code,tmdb_provider_id) IN (('DISNEY_PLUS',337),('WATCHA',97),('WAVVE',356),('TVING',1883))")" == 4 ]]
  run_ingestion "$test_dir/ott" --raw-dir "$test_dir/raw" --ott-mapping "$test_dir/approved.csv" >/dev/null
  [[ "$(db -qAt -c 'SELECT o.tmdb_provider_id FROM movie_ott_providers p JOIN otts o ON o.id=p.ott_id ORDER BY o.tmdb_provider_id')" == 8 ]]
  jq -e '.unknown_ott_provider_occurrences == 1' "$test_dir/ott/run-manifest.json" >/dev/null
  [[ "$(db -qAt -c 'SELECT count(*) FROM movie_genres')" == 1 ]]
  [[ "$(db -qAt -c 'SELECT count(*) FROM people')" == 1 ]]
  [[ "$(db -qAt -c 'SELECT count(*) FROM movie_people')" == 2 ]]
  snapshot >"$test_dir/before.json"
  run_ingestion "$test_dir/ott" --raw-dir "$test_dir/raw" --ott-mapping "$test_dir/approved.csv" >/dev/null
  jq -e '.postgresql.skipped == 1 and .postgresql.succeeded == 0' "$test_dir/ott/run-manifest.json" >/dev/null
  snapshot >"$test_dir/after.json"
  cmp "$test_dir/before.json" "$test_dir/after.json"
  # An absent CSV must preserve an existing relation even when the current raw has no offers.
  jq '.providers.results = {}' "$test_dir/good.json" >"$test_dir/raw/2.json"
  run_ingestion "$test_dir/ott" --raw-dir "$test_dir/raw" --refresh >/dev/null
  [[ "$(db -qAt -c 'SELECT count(*) FROM movie_ott_providers')" == 1 ]]
  jq -e '.postgresql.updated == 1 and .postgresql.inserted == 0' "$test_dir/ott/run-manifest.json" >/dev/null
fi

if [[ "$group" == all || "$group" == invalid ]]; then
  printf 'ingestion regression: invalid raw and individual API responses\n'
  cp "$test_dir/good.json" "$test_dir/raw/2.json"
  run_ingestion "$test_dir/seed" --raw-dir "$test_dir/raw" --ott-mapping "$test_dir/approved.csv" >/dev/null
  snapshot >"$test_dir/before.json"
  case_number=0
  while IFS= read -r filter; do
    case_number=$((case_number + 1))
    jq "$filter" "$test_dir/good.json" >"$RESPONSE_FILE"
    cp "$RESPONSE_FILE" "$test_dir/raw/2.json"
    for mode in raw api api-fresh; do
      output="$test_dir/invalid-$case_number-$mode"
      mkdir -p "$output/raw-tmdb"
      if [[ "$mode" != api-fresh ]]; then
        cp "$test_dir/good.json" "$output/raw-tmdb/2.json"
        printf '2\n' >"$output/checkpoint-success.txt"
      fi
      args=()
      [[ "$mode" != raw ]] || args+=(--raw-dir "$test_dir/raw")
      : >"$SQL_LOG"
      expect_failure run_ingestion "$output" --refresh --ott-mapping "$test_dir/approved.csv" "${args[@]}"
      jq -e '.postgresql.succeeded == 0 and .postgresql.failed == 1 and .postgresql.status != "SUCCESS"' "$output/run-manifest.json" >/dev/null
      jq -e 'length == 1 and .[0].tmdb_id == 2 and (.[0].reason | length > 0)' "$output/failure-report.json" >/dev/null
      # The approved catalog now has its own transaction before raw validation.
      # Exactly that one may begin; invalid raw must never start a movie transaction.
      if grep -Eq 'DELETE FROM movie_|INSERT INTO movies' "$SQL_LOG" \
          || [[ "$(grep -c '^BEGIN;$' "$SQL_LOG")" != 1 ]]; then
        printf 'invalid response reached movie transaction: %s (%s)\n' "$filter" "$mode" >&2; exit 1
      fi
      if [[ "$mode" == api-fresh ]]; then
        [[ ! -e "$output/raw-tmdb/2.json" && ! -s "$output/checkpoint-success.txt" ]]
      else
        cmp "$test_dir/good.json" "$output/raw-tmdb/2.json"
      fi
      [[ ! -e "$output/raw-tmdb/2.json.tmp" ]]
      snapshot >"$test_dir/after.json"
      cmp "$test_dir/before.json" "$test_dir/after.json"
    done
    printf 'invalid case %s passed\n' "$case_number"
  done <<'FILTERS'
del(.credits)
del(.providers)
del(.credits.cast)
del(.credits.crew)
del(.providers.results)
del(.details.genres)
del(.details.production_countries)
.credits.cast = null
.credits.crew = {}
.providers.results = []
.details.genres = {}
.details.production_countries = null
.credits = []
.providers = null
.details = []
.details.id = 99
.credits.id = 99
.providers.id = 99
del(.credits.id)
del(.providers.id)
FILTERS
  # Empty arrays and an absent KR are valid and intentionally clear the relations.
  jq '.details.genres=[] | .details.production_countries=[] | .credits.cast=[] | .credits.crew=[] | .providers.results={}' \
    "$test_dir/good.json" >"$test_dir/raw/2.json"
  run_ingestion "$test_dir/empty" --raw-dir "$test_dir/raw" --ott-mapping "$test_dir/approved.csv" >/dev/null
  [[ "$(db -qAt -c 'SELECT (SELECT count(*) FROM movie_genres)+(SELECT count(*) FROM movie_countries)+(SELECT count(*) FROM movie_people)+(SELECT count(*) FROM movie_ott_providers)')" == 0 ]]
fi

if [[ "$group" == all || "$group" == all-failed ]]; then
  printf 'ingestion regression: all and partial failure reports\n'
  db -q -c 'TRUNCATE movies RESTART IDENTITY CASCADE' >/dev/null 2>&1
  printf '3\n4\n' >"$test_dir/ids.txt"
  expect_failure run_ingestion "$test_dir/all-failed" --raw-dir "$test_dir/raw"
  jq -e 'length == 2 and map(.tmdb_id) == [3,4] and all(.[]; .reason == "RAW_FILE_MISSING_OR_INVALID")' "$test_dir/all-failed/failure-report.json" >/dev/null
  jq -e '.input_ids == 2 and .postgresql.succeeded == 0 and .postgresql.failed == 2
    and .postgresql.skipped == 0 and .postgresql.status != "SUCCESS" and .status == "FAILED"
    and .service_movie_export_rows == null and .service_movie_export_sha256 == null
    and .service_movie_export.status == "FAILED"' "$test_dir/all-failed/run-manifest.json" >/dev/null
  [[ -s "$test_dir/all-failed/run-summary.txt" && ! -e "$test_dir/all-failed/service-movies.csv" ]]
  [[ ! -s "$test_dir/all-failed/checkpoint-success.txt" ]]
  cp "$test_dir/good.json" "$test_dir/raw/2.json"
  printf '2\n3\n' >"$test_dir/ids.txt"
  expect_failure run_ingestion "$test_dir/partial" --raw-dir "$test_dir/raw"
  jq -e '.postgresql.succeeded == 1 and .postgresql.failed == 1 and .postgresql.skipped == 0
    and .service_movie_export_rows == 1 and .service_movie_export.status == "SUCCESS"' "$test_dir/partial/run-manifest.json" >/dev/null
  [[ "$(cat "$test_dir/partial/checkpoint-success.txt")" == 2 ]]
  expect_failure run_ingestion "$test_dir/partial" --raw-dir "$test_dir/raw"
  jq -e '.postgresql.succeeded == 0 and .postgresql.failed == 1 and .postgresql.skipped == 1' "$test_dir/partial/run-manifest.json" >/dev/null
fi

if [[ "$group" == all || "$group" == export ]]; then
  printf 'ingestion regression: export failure diagnostics and preservation\n'
  printf '2\n' >"$test_dir/ids.txt"
  cp "$test_dir/good.json" "$test_dir/raw/2.json"
  for mode in query header duplicate publish sample; do
    output="$test_dir/export-$mode"
    mkdir -p "$output"
    printf 'previous export\n' >"$test_dir/previous.csv"
    cp "$test_dir/previous.csv" "$output/service-movies.csv"
    if [[ "$mode" == publish ]]; then
      rm "$output/service-movies.csv"
      mkdir "$output/service-movies.csv"
    fi
    export EXPORT_MODE="$mode"
    expect_failure run_ingestion "$output" --raw-dir "$test_dir/raw"
    unset EXPORT_MODE
    jq -e '.status == "FAILED" and .postgresql.succeeded == 1 and .postgresql.failed == 0' "$output/run-manifest.json" >/dev/null
    [[ -s "$output/failure-report.json" && -s "$output/run-summary.txt" ]]
    if [[ "$mode" == sample ]]; then
      jq -e '.service_movie_export.status == "SUCCESS" and .postgres_sample.status == "FAILED"
        and .postgres_sample_rows == null' "$output/run-manifest.json" >/dev/null
    else
      jq -e '.service_movie_export.status == "FAILED" and (.service_movie_export.reason | length > 0)
        and .service_movie_export_rows == null and .service_movie_export_sha256 == null' "$output/run-manifest.json" >/dev/null
      if [[ "$mode" != publish ]]; then cmp "$test_dir/previous.csv" "$output/service-movies.csv"; fi
    fi
  done
fi

if grep -RFq -- "$TMDB_TOKEN" "$SQL_LOG" "$CURL_LOG" "$test_dir"/*.log; then
  printf 'token leaked into test logs\n' >&2; exit 1
fi
printf 'TMDb ingestion regression tests passed (%s)\n' "$group"
