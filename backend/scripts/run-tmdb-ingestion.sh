#!/usr/bin/env bash
set -uo pipefail

readonly POSTGRES_CONTAINER_DEFAULT='feelm-postgres'
readonly TMDB_API_BASE="${TMDB_API_BASE:-https://api.themoviedb.org/3}"
readonly TMDB_IMAGE_BASE="${TMDB_IMAGE_BASE:-https://image.tmdb.org/t/p/original}"
readonly TMDB_POSTER_IMAGE_BASE="${TMDB_POSTER_IMAGE_BASE:-https://image.tmdb.org/t/p/w500}"
readonly TMDB_BACKDROP_IMAGE_BASE="${TMDB_BACKDROP_IMAGE_BASE:-https://image.tmdb.org/t/p/w1280}"

usage() {
  cat <<'USAGE'
Usage:
  TMDB_TOKEN=... run-tmdb-ingestion.sh \
    --input FILE --output-dir DIR [options]

Required:
  --input FILE              SSAFY pipe-separated CSV with an id column, or one TMDb ID per line
  --output-dir DIR          Persistent directory for raw JSON, checkpoint, reports and export CSV

Options:
  --ott-mapping FILE        Approved CSV with exact header: tmdb_provider_id,code,name,logo_path
  --raw-dir DIR             Reuse collected <tmdb_id>.json files instead of calling TMDb
  --postgres-mode MODE      docker or direct (default: docker)
  --postgres-container NAME PostgreSQL container (default: feelm-postgres)
  --limit N                 Process only the first N IDs (default: 0, all)
  --refresh                 Reprocess IDs already present in the successful DB checkpoint
  --dry-run                 Fetch and validate TMDb, but do not change PostgreSQL/checkpoint
  --validate-only           Validate input and write its manifest without TMDb/PostgreSQL access
  --help                    Show this help

Exit codes:
  0 success, 2 configuration/input/catalog failure, 3 movie or output failure

The token is injected through the process environment. The script never writes it to output files.
Only approved providers in watch/providers.results.KR.flatrate are synchronized.
Direct PostgreSQL mode uses libpq PGHOST, PGPORT, PGDATABASE and PGUSER.
Provide the password through PGPASSFILE or PGPASSWORD; never put it in command arguments.
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

atomic_replace() {
  local source="$1" target="$2"
  mv -fT -- "$source" "$target"
}

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

input=''
output_dir=''
ott_mapping=''
raw_dir=''
postgres_mode='docker'
postgres_container="$POSTGRES_CONTAINER_DEFAULT"
limit='0'
refresh='false'
dry_run='false'
validate_only='false'

while (($#)); do
  case "$1" in
    --input) (($# >= 2)) || fail '--input requires a value'; input="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || fail '--output-dir requires a value'; output_dir="$2"; shift 2 ;;
    --ott-mapping) (($# >= 2)) || fail '--ott-mapping requires a value'; ott_mapping="$2"; shift 2 ;;
    --raw-dir) (($# >= 2)) || fail '--raw-dir requires a value'; raw_dir="$2"; shift 2 ;;
    --postgres-mode) (($# >= 2)) || fail '--postgres-mode requires a value'; postgres_mode="$2"; shift 2 ;;
    --postgres-container) (($# >= 2)) || fail '--postgres-container requires a value'; postgres_container="$2"; shift 2 ;;
    --limit) (($# >= 2)) || fail '--limit requires a value'; limit="$2"; shift 2 ;;
    --refresh) refresh='true'; shift ;;
    --dry-run) dry_run='true'; shift ;;
    --validate-only) validate_only='true'; shift ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

require_command awk
require_command base64
require_command grep
require_command head
require_command realpath
require_command sha256sum
require_command stat
require_command sort
require_command uniq

[[ -n "$input" ]] || fail '--input is required'
[[ -n "$output_dir" ]] || fail '--output-dir is required'
[[ -r "$input" && -f "$input" ]] || fail 'input file is not readable'
[[ "$limit" =~ ^[0-9]+$ ]] || fail '--limit must be zero or a positive integer'
[[ "$postgres_mode" == 'docker' || "$postgres_mode" == 'direct' ]] \
  || fail '--postgres-mode must be docker or direct'
[[ "$postgres_container" =~ ^[A-Za-z0-9_.-]+$ ]] || fail 'invalid PostgreSQL container name'
if [[ -n "$ott_mapping" ]]; then
  [[ -r "$ott_mapping" && -f "$ott_mapping" ]] || fail 'OTT mapping file is not readable'
fi
if [[ -n "$raw_dir" ]]; then
  [[ -d "$raw_dir" ]] || fail '--raw-dir must reference a readable directory'
fi

mkdir -p "$output_dir/raw-tmdb" || fail 'cannot create output directory'
input="$(realpath "$input")" || fail 'cannot resolve input path'
output_dir="$(realpath "$output_dir")" || fail 'cannot resolve output directory'
if [[ -n "$raw_dir" ]]; then
  raw_dir="$(realpath "$raw_dir")" || fail 'cannot resolve raw JSON directory'
fi

# Keep staging files on the output filesystem so publication is an atomic rename.
work_dir="$(mktemp -d "$output_dir/.tmdb-ingestion.XXXXXX")" || fail 'cannot create temporary work directory'
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

ids_file="$work_dir/tmdb-ids.txt"
first_nonblank="$(awk 'NF {print; exit}' "$input")"
if [[ "$first_nonblank" == *'|'* ]]; then
  awk -F'|' '
    NR == 1 {
      sub(/^\xef\xbb\xbf/, "", $1)
      id_column = 0
      for (i = 1; i <= NF; i++) {
        value = $i
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        if (value == "id") id_column = i
      }
      if (id_column == 0) exit 41
      next
    }
    /^[[:space:]]*$/ { next }
    {
      value = $id_column
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      if (value == "") exit 42
      print value
    }
  ' "$input" >"$ids_file"
  parse_status=$?
  [[ "$parse_status" -ne 41 ]] || fail 'pipe-separated input is missing the id header'
  [[ "$parse_status" -ne 42 ]] || fail 'pipe-separated input contains a blank id'
  [[ "$parse_status" -eq 0 ]] || fail 'cannot parse pipe-separated input'
else
  awk '
    { sub(/^\xef\xbb\xbf/, "", $0) }
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0); print }
  ' "$input" >"$ids_file"
fi

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
input_count="$(awk 'END {print NR}' "$ids_file")"
input_manifest_tmp="$work_dir/input-manifest.json"
{
  printf '{\n'
  printf '  "file_name": "%s",\n' "$(json_escape "$(basename "$input")")"
  printf '  "byte_size": %d,\n' "$input_bytes"
  printf '  "sha256": "%s",\n' "$input_sha256"
  printf '  "selected_tmdb_ids": %d\n' "$input_count"
  printf '}\n'
} >"$input_manifest_tmp"
atomic_replace "$input_manifest_tmp" "$output_dir/input-manifest.json" \
  || fail 'cannot publish input manifest'

if [[ "$validate_only" == 'true' ]]; then
  printf 'Input validation complete: ids=%d bytes=%d sha256=%s\n' \
    "$input_count" "$input_bytes" "$input_sha256" \
    | tee "$output_dir/run-summary.txt"
  exit 0
fi

require_command jq
if [[ -z "$raw_dir" ]]; then
  require_command curl
  [[ -n "${TMDB_TOKEN:-}" ]] || fail 'TMDB_TOKEN environment variable is required'
  [[ "$TMDB_TOKEN" =~ ^[A-Za-z0-9._-]+$ ]] \
    || fail 'TMDB_TOKEN contains unsupported characters'
fi

postgres_psql() {
  if [[ "$postgres_mode" == 'docker' ]]; then
    docker exec -i "$postgres_container" sh -c \
      'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' \
      sh "$@"
  else
    PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-10}" \
      psql -v ON_ERROR_STOP=1 "$@"
  fi
}

if [[ "$dry_run" == 'false' ]]; then
  if [[ "$postgres_mode" == 'docker' ]]; then
    require_command docker
    docker inspect "$postgres_container" >/dev/null 2>&1 \
      || fail "PostgreSQL container is not running: $postgres_container"
    docker exec "$postgres_container" sh -c \
      'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null \
      || fail 'PostgreSQL is not ready'
  else
    require_command psql
    for variable in PGHOST PGPORT PGDATABASE PGUSER; do
      [[ -n "${!variable:-}" ]] || fail "$variable is required in direct PostgreSQL mode"
    done
    [[ "$PGPORT" =~ ^[1-9][0-9]{0,4}$ && "$PGPORT" -le 65535 ]] \
      || fail 'PGPORT must be between 1 and 65535'
    [[ -n "${PGPASSFILE:-}" || -n "${PGPASSWORD:-}" ]] \
      || fail 'PGPASSFILE or PGPASSWORD is required in direct PostgreSQL mode'
    [[ -z "${PGPASSFILE:-}" || -r "$PGPASSFILE" ]] \
      || fail 'PGPASSFILE is not readable'
    connection_ready="$(printf 'SELECT 1;\n' | postgres_psql -qAt 2>/dev/null)" \
      || fail 'cannot connect to PostgreSQL in direct mode; verify the SSH tunnel and account'
    [[ "$connection_ready" == '1' ]] || fail 'unexpected PostgreSQL connection check result'
  fi
  schema_ready="$(postgres_psql -qAt <<'SQL'
SELECT (
  to_regclass('public.movies') IS NOT NULL
  AND to_regclass('public.genres') IS NOT NULL
  AND to_regclass('public.movie_genres') IS NOT NULL
  AND to_regclass('public.movie_countries') IS NOT NULL
  AND to_regclass('public.people') IS NOT NULL
  AND to_regclass('public.movie_people') IS NOT NULL
  AND to_regclass('public.otts') IS NOT NULL
  AND to_regclass('public.movie_ott_providers') IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.movies'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (tmdb_id)'
  )
)::int;
SQL
)" || fail 'cannot verify PostgreSQL schema'
  [[ "$schema_ready" == '1' ]] \
    || fail 'required movie migrations or movies.tmdb_id unique constraint are missing'
fi

declare -A approved_ott_ids=()
if [[ -n "$ott_mapping" ]]; then
  declare -A approved_ott_codes=()
  header="$(head -n 1 "$ott_mapping" | tr -d '\r\357\273\277')"
  [[ "$header" == 'tmdb_provider_id,code,name,logo_path' ]] \
    || fail 'OTT mapping header must be exactly tmdb_provider_id,code,name,logo_path'
  tail -n +2 "$ott_mapping" >"$work_dir/ott-rows.csv" || fail 'cannot read OTT mapping'
  catalog_sql="$work_dir/ott-catalog.sql"
  cat >"$catalog_sql" <<'SQL' || fail 'cannot stage OTT catalog transaction'
BEGIN;
CREATE TEMP TABLE _ott_catalog_input (
  position int GENERATED ALWAYS AS IDENTITY,
  code varchar(30) NOT NULL UNIQUE, name text NOT NULL, logo_url text NOT NULL,
  tmdb_provider_id int NOT NULL UNIQUE
) ON COMMIT DROP;
SQL
  # Parse every row before any catalog write, including an unterminated final line.
  while IFS= read -r mapping_row || [[ -n "$mapping_row" ]]; do
    mapping_row="${mapping_row%$'\r'}"
    [[ -z "$mapping_row" ]] && continue
    separators="${mapping_row//[^,]/}"
    [[ "${#separators}" == 3 ]] || fail 'OTT mapping must contain exactly four columns without commas in values'
    IFS=',' read -r provider_id provider_code provider_name logo_path <<<"$mapping_row"
    [[ "$provider_id" =~ ^[1-9][0-9]{0,9}$ && "$provider_id" -le 2147483647 ]] \
      || fail 'invalid or out-of-range OTT tmdb_provider_id'
    [[ "$provider_code" =~ ^[A-Z0-9_]{1,30}$ ]] || fail 'invalid OTT code'
    [[ "$provider_name" =~ [^[:space:]] ]] || fail 'blank OTT name'
    [[ "$logo_path" =~ ^/[A-Za-z0-9._/-]+$ && "$logo_path" != *'..'* ]] \
      || fail 'OTT logo_path must be a non-empty TMDb path beginning with /'
    [[ -z "${approved_ott_ids[$provider_id]:-}" ]] || fail "duplicate OTT provider: $provider_id"
    [[ -z "${approved_ott_codes[$provider_code]:-}" ]] || fail "duplicate OTT code: $provider_code"
    approved_ott_ids[$provider_id]=1
    approved_ott_codes[$provider_code]=1
    if [[ "$dry_run" == 'false' ]]; then
      code_b64="$(printf '%s' "$provider_code" | base64 -w0)"
      name_b64="$(printf '%s' "$provider_name" | base64 -w0)"
      logo_url_b64="$(printf '%s' "$TMDB_IMAGE_BASE$logo_path" | base64 -w0)"
      cat >>"$catalog_sql" <<SQL || fail 'cannot stage OTT catalog row'
INSERT INTO _ott_catalog_input (code, name, logo_url, tmdb_provider_id)
VALUES (
  convert_from(decode('$code_b64', 'base64'), 'UTF8'),
  convert_from(decode('$name_b64', 'base64'), 'UTF8'),
  convert_from(decode('$logo_url_b64', 'base64'), 'UTF8'),
  $provider_id
);
SQL
    fi
  done <"$work_dir/ott-rows.csv"
  ((${#approved_ott_ids[@]} > 0)) || fail 'OTT mapping contains no providers'
  if [[ "$dry_run" == 'false' ]]; then
    cat >>"$catalog_sql" <<'SQL' || fail 'cannot complete OTT catalog transaction'
-- Serialize catalog writers through validation and commit. Existing-ID FK reads
-- (subscriptions/history) remain possible; no identity or relationship is changed.
LOCK TABLE otts IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM otts o JOIN _ott_catalog_input i
      ON o.code = i.code OR o.tmdb_provider_id = i.tmdb_provider_id
    WHERE o.code <> i.code OR o.tmdb_provider_id <> i.tmdb_provider_id
  ) THEN
    RAISE EXCEPTION 'OTT code/provider identity conflict; catalog unchanged';
  END IF;
END $$;
UPDATE otts o SET name = i.name, logo_url = i.logo_url
FROM _ott_catalog_input i
WHERE o.code = i.code AND o.tmdb_provider_id = i.tmdb_provider_id
  AND (o.name, o.logo_url) IS DISTINCT FROM (i.name, i.logo_url);
INSERT INTO otts (code, name, logo_url, tmdb_provider_id)
SELECT i.code, i.name, i.logo_url, i.tmdb_provider_id
FROM _ott_catalog_input i
WHERE NOT EXISTS (SELECT 1 FROM otts o WHERE o.code = i.code)
ORDER BY i.position;
COMMIT;
SQL
    postgres_psql -q <"$catalog_sql" >/dev/null \
      || fail 'cannot load approved OTT catalog; transaction rolled back unless commit acknowledgement was lost'
  fi
fi
# Only numeric IDs that passed the CSV validation above may enter this SQL list.
# NULL keeps the SQL valid when OTT synchronization is disabled.
approved_ott_sql='NULL'
if ((${#approved_ott_ids[@]} > 0)); then
  approved_ott_sql="$(IFS=,; printf '%s' "${!approved_ott_ids[*]}")"
fi

checkpoint="$output_dir/checkpoint-success.txt"
if [[ -e "$checkpoint" && ! -f "$checkpoint" ]]; then
  fail 'checkpoint path is not a regular file'
fi
if [[ ! -e "$checkpoint" ]]; then
  if [[ "$dry_run" == 'true' ]]; then
    checkpoint="$work_dir/checkpoint-success.txt"
  fi
  : >"$checkpoint" || fail 'cannot create checkpoint file'
fi
if ! awk '/^[1-9][0-9]*$/ {next} /^[[:space:]]*$/ {next} {exit 1}' "$checkpoint"; then
  fail 'checkpoint contains an invalid TMDb ID'
fi

tmdb_get() {
  local endpoint="$1" destination="$2" attempt=0 status header_file body_file retry_after delay
  header_file="$work_dir/headers"
  body_file="$work_dir/body"
  while :; do
    : >"$header_file"
    : >"$body_file"
    status="$({ printf 'header = "Authorization: Bearer %s"\n' "$TMDB_TOKEN"; } \
      | curl --config - --silent --show-error --location \
          --connect-timeout 10 --max-time 30 \
          --dump-header "$header_file" --output "$body_file" \
          --write-out '%{http_code}' "$TMDB_API_BASE/$endpoint")" || status='000'
    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      mv "$body_file" "$destination"
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

inserted=0
updated=0
skipped=0
succeeded=0
failed=0
unknown_ott=0
failures_tsv="$work_dir/failures.tsv"
: >"$failures_tsv"

while IFS= read -r tmdb_id; do
  if [[ "$refresh" == 'false' ]] && grep -Fxq "$tmdb_id" "$checkpoint"; then
    skipped=$((skipped + 1))
    continue
  fi

  if [[ -n "$raw_dir" ]]; then
    raw_file="$raw_dir/$tmdb_id.json"
    if ! valid_raw "$tmdb_id" "$raw_file"; then
      printf '%s\tRAW_FILE_MISSING_OR_INVALID\n' "$tmdb_id" >>"$failures_tsv"
      failed=$((failed + 1))
      continue
    fi
  else
    detail="$work_dir/$tmdb_id-detail.json"
    credits="$work_dir/$tmdb_id-credits.json"
    providers="$work_dir/$tmdb_id-providers.json"
    status=''
    status="$(tmdb_get "movie/$tmdb_id?language=ko-KR" "$detail")" \
      || { printf '%s\tTMDB_DETAIL_HTTP_%s\n' "$tmdb_id" "$status" >>"$failures_tsv"; failed=$((failed + 1)); continue; }
    status="$(tmdb_get "movie/$tmdb_id/credits?language=ko-KR" "$credits")" \
      || { printf '%s\tTMDB_CREDITS_HTTP_%s\n' "$tmdb_id" "$status" >>"$failures_tsv"; failed=$((failed + 1)); continue; }
    status="$(tmdb_get "movie/$tmdb_id/watch/providers" "$providers")" \
      || { printf '%s\tTMDB_PROVIDERS_HTTP_%s\n' "$tmdb_id" "$status" >>"$failures_tsv"; failed=$((failed + 1)); continue; }

    raw_file="$output_dir/raw-tmdb/$tmdb_id.json"
    if ! jq -n --slurpfile details "$detail" --slurpfile credits "$credits" \
        --slurpfile providers "$providers" \
        '{details:$details[0],credits:$credits[0],providers:$providers[0]}' \
        >"$raw_file.tmp" 2>/dev/null \
        || ! valid_raw "$tmdb_id" "$raw_file.tmp"; then
      rm -f -- "$raw_file.tmp"
      printf '%s\tINVALID_TMDB_JSON\n' "$tmdb_id" >>"$failures_tsv"
      failed=$((failed + 1))
      continue
    fi
    if ! atomic_replace "$raw_file.tmp" "$raw_file"; then
      printf '%s\tRAW_FILE_WRITE_FAILED\n' "$tmdb_id" >>"$failures_tsv"
      failed=$((failed + 1))
      continue
    fi
  fi

  payload="$work_dir/$tmdb_id-payload.json"
  # Keep the complete provider response in raw-tmdb, but synchronize only KR subscription offers.
  # results.KR.link is a regional TMDb/JustWatch page, not a provider-specific deep link.
  if ! jq \
      --arg poster_image_base "$TMDB_POSTER_IMAGE_BASE" \
      --arg backdrop_image_base "$TMDB_BACKDROP_IMAGE_BASE" '
      .details as $d | .credits as $c | .providers as $p |
      (($d.title // "") | if length > 0 then . else ($d.original_title // "") end) as $title |
      if ($title | length) == 0 then error("missing title") else
      {
        tmdb_id: ($d.id | tonumber), title: $title,
        original_title: ($d.original_title // null), synopsis: ($d.overview // null),
        release_date: (($d.release_date // "") | if length > 0 then . else null end),
        runtime_minutes: (($d.runtime // 0) | if . > 0 then . else null end),
        poster_url: (($d.poster_path // "") | if length > 0 then $poster_image_base + . else null end),
        backdrop_url: (($d.backdrop_path // "") | if length > 0 then $backdrop_image_base + . else null end),
        # TMDb can occasionally return the same genre ID more than once for one movie.
        # Keep one row per ID so PostgreSQL upsert never targets the same genre twice.
        genres: ([($d.genres // [])[] | select(.id > 0 and (.name | length) > 0) | {id,name}] | unique_by(.id)),
        countries: [($d.production_countries // [])[] | .iso_3166_1 | select(test("^[A-Z]{2}$"))] | unique,
        people: ((
          [($c.crew // [])[] | select(.job == "Director") |
            {id,name,profile_path,role:"DIRECTOR",character:null,display_order:null}] +
          [($c.cast // [])[] | {id,name,profile_path,role:"ACTOR",
            character:(.character // null),display_order:(.order // null)}]
        ) | map(select(.id > 0 and (.name | length) > 0)) | unique_by([.id,.role])),
        providers: ([($p.results.KR.flatrate // [])[] |
            {id:.provider_id,name:.provider_name,logo_path:(.logo_path // null),
             watch_url:($p.results.KR.link // null)}] | unique_by(.id))
      } end
    ' "$raw_file" >"$payload"; then
    printf '%s\tINVALID_TMDB_METADATA\n' "$tmdb_id" >>"$failures_tsv"
    failed=$((failed + 1))
    continue
  fi

  while IFS= read -r provider_id; do
    [[ -n "${approved_ott_ids[$provider_id]:-}" ]] || unknown_ott=$((unknown_ott + 1))
  done < <(jq -r '.providers[].id' "$payload")

  if [[ "$dry_run" == 'true' ]]; then
    succeeded=$((succeeded + 1))
    continue
  fi

  existed="$(printf 'SELECT EXISTS (SELECT 1 FROM movies WHERE tmdb_id = %s);\n' "$tmdb_id" \
    | postgres_psql -qAt)" \
    || { printf '%s\tDB_EXISTENCE_CHECK_FAILED\n' "$tmdb_id" >>"$failures_tsv"; failed=$((failed + 1)); continue; }
  payload_b64="$(base64 -w0 "$payload")"
  ott_sync='false'
  [[ -n "$ott_mapping" ]] && ott_sync='true'

  if ! postgres_psql -q <<SQL >/dev/null
BEGIN;
CREATE TEMP TABLE _tmdb_payload (data jsonb) ON COMMIT DROP;
INSERT INTO _tmdb_payload
VALUES (convert_from(decode('$payload_b64', 'base64'), 'UTF8')::jsonb);

CREATE TEMP TABLE _movie_id ON COMMIT DROP AS
WITH upserted AS (
  INSERT INTO movies (
    tmdb_id, title, original_title, synopsis, release_date,
    runtime_minutes, poster_url, backdrop_url
  )
  SELECT
    (data->>'tmdb_id')::int,
    data->>'title',
    data->>'original_title',
    data->>'synopsis',
    NULLIF(data->>'release_date', '')::date,
    NULLIF(data->>'runtime_minutes', '')::int,
    data->>'poster_url',
    data->>'backdrop_url'
  FROM _tmdb_payload
  ON CONFLICT (tmdb_id) DO UPDATE SET
    title = EXCLUDED.title,
    original_title = EXCLUDED.original_title,
    synopsis = EXCLUDED.synopsis,
    release_date = EXCLUDED.release_date,
    runtime_minutes = EXCLUDED.runtime_minutes,
    poster_url = EXCLUDED.poster_url,
    backdrop_url = EXCLUDED.backdrop_url
  RETURNING id
)
SELECT id FROM upserted;

INSERT INTO genres (code, name, tmdb_genre_id)
SELECT
  'TMDB_' || genre_rows.tmdb_genre_id,
  genre_rows.name,
  genre_rows.tmdb_genre_id
FROM (
  SELECT (genre->>'id')::int AS tmdb_genre_id, max(genre->>'name') AS name
  FROM _tmdb_payload, jsonb_array_elements(data->'genres') AS genre
  GROUP BY (genre->>'id')::int
) AS genre_rows
ON CONFLICT (tmdb_genre_id) DO UPDATE SET name = EXCLUDED.name;
DELETE FROM movie_genres WHERE movie_id = (SELECT id FROM _movie_id);
INSERT INTO movie_genres (movie_id, genre_id)
SELECT DISTINCT (SELECT id FROM _movie_id), genres.id
FROM _tmdb_payload, jsonb_array_elements(data->'genres') AS genre
JOIN genres ON genres.tmdb_genre_id = (genre->>'id')::int;

DELETE FROM movie_countries WHERE movie_id = (SELECT id FROM _movie_id);
INSERT INTO movie_countries (movie_id, country_code)
SELECT (SELECT id FROM _movie_id), country.value
FROM _tmdb_payload, jsonb_array_elements_text(data->'countries') AS country(value);

WITH person_rows AS (
  SELECT
    (person->>'id')::int AS tmdb_person_id,
    person->>'name' AS name,
    CASE WHEN NULLIF(person->>'profile_path', '') IS NULL THEN NULL
         ELSE '$TMDB_IMAGE_BASE' || (person->>'profile_path') END AS profile_url
  FROM _tmdb_payload, jsonb_array_elements(data->'people') AS person
), deduplicated_people AS (
  -- The same person can be both director and actor in one movie. Upsert each
  -- TMDb person only once, while movie_people keeps both role rows below.
  SELECT tmdb_person_id, max(name) AS name, max(profile_url) AS profile_url
  FROM person_rows
  GROUP BY tmdb_person_id
)
INSERT INTO people (tmdb_person_id, name, profile_url)
SELECT tmdb_person_id, name, profile_url
FROM deduplicated_people
ON CONFLICT (tmdb_person_id) DO UPDATE
SET name = EXCLUDED.name, profile_url = EXCLUDED.profile_url;
DELETE FROM movie_people WHERE movie_id = (SELECT id FROM _movie_id);
INSERT INTO movie_people (movie_id, person_id, role, character_name, display_order)
SELECT
  (SELECT id FROM _movie_id), people.id, person->>'role',
  person->>'character', NULLIF(person->>'display_order', '')::int
FROM _tmdb_payload, jsonb_array_elements(data->'people') AS person
JOIN people ON people.tmdb_person_id = (person->>'id')::int;

DO \$block\$
BEGIN
  IF $ott_sync THEN
    DELETE FROM movie_ott_providers WHERE movie_id = (SELECT id FROM _movie_id);
    INSERT INTO movie_ott_providers (movie_id, ott_id, watch_url, synced_at)
    SELECT
      (SELECT id FROM _movie_id), otts.id, provider->>'watch_url', now()
    FROM _tmdb_payload, jsonb_array_elements(data->'providers') AS provider
    JOIN otts ON otts.tmdb_provider_id = (provider->>'id')::int
    WHERE otts.tmdb_provider_id IN ($approved_ott_sql);
  END IF;
END
\$block\$;

UPDATE movies SET synced_at = now() WHERE id = (SELECT id FROM _movie_id);
COMMIT;
SQL
  then
    printf '%s\tDB_TRANSACTION_FAILED\n' "$tmdb_id" >>"$failures_tsv"
    failed=$((failed + 1))
    continue
  fi

  if [[ "$existed" == 't' ]]; then
    updated=$((updated + 1))
  else
    inserted=$((inserted + 1))
  fi
  succeeded=$((succeeded + 1))
  { cat "$checkpoint"; printf '%s\n' "$tmdb_id"; } | sort -nu >"$work_dir/checkpoint"
  atomic_replace "$work_dir/checkpoint" "$checkpoint" \
    || fail "movie $tmdb_id was committed but checkpoint could not be updated"
done <"$ids_file"

# Preserve movie-level failures before any export can fail.
failure_json="$work_dir/failure-report.json"
jq -Rn '[inputs | split("\t") | {tmdb_id:(.[0]|tonumber),reason:.[1]}]' \
  <"$failures_tsv" >"$failure_json" || fail 'cannot serialize failure report'
atomic_replace "$failure_json" "$output_dir/failure-report.json" \
  || fail 'cannot publish failure report'

export_rows='null'
export_sha256='null'
sample_rows='null'
export_state='NOT_RUN'
export_error=''
sample_state='NOT_RUN'
sample_error=''

export_service_movies() {
  local export_tmp header candidate_rows candidate_sha256
  export_tmp="$work_dir/service-movies.csv"
  postgres_psql -q \
    -c "COPY (SELECT id AS service_movie_id, tmdb_id FROM movies ORDER BY id) TO STDOUT WITH CSV HEADER" \
    >"$export_tmp" || { export_error='QUERY_FAILED'; return 1; }
  header="$(head -n 1 "$export_tmp" | tr -d '\r')"
  [[ "$header" == 'service_movie_id,tmdb_id' ]] \
    || { export_error='INVALID_HEADER'; return 1; }
  candidate_rows="$(awk -F',' '
    NR == 1 { next }
    NF != 2 || $1 !~ /^[1-9][0-9]*$/ || $2 !~ /^[1-9][0-9]*$/ { exit 1 }
    seen_service[$1]++ { exit 2 }
    seen_tmdb[$2]++ { exit 3 }
    { count++ }
    END { if (count == 0) exit 4; print count }
  ' "$export_tmp")" || { export_error='INVALID_ID_MAPPING'; return 1; }
  candidate_sha256="$(sha256sum "$export_tmp" | awk '{print $1}')" \
    || { export_error='CHECKSUM_FAILED'; return 1; }
  atomic_replace "$export_tmp" "$output_dir/service-movies.csv" \
    || { export_error='PUBLISH_FAILED'; return 1; }
  export_rows="$candidate_rows"
  export_sha256="$candidate_sha256"
}

export_postgres_sample() {
  local sample_ids sample_tmp candidate_rows
  sample_ids="$(awk 'BEGIN { separator = "" } NR <= 5 {
    printf "%s%s", separator, $0
    separator = ","
  } END { print "" }' "$ids_file")"
  sample_tmp="$work_dir/postgres-sample.csv"
  postgres_psql -q \
      -c "COPY (
        SELECT
          m.id AS service_movie_id,
          m.tmdb_id,
          m.title,
          (SELECT count(*) FROM movie_genres mg WHERE mg.movie_id = m.id) AS genre_count,
          (SELECT count(*) FROM movie_countries mc WHERE mc.movie_id = m.id) AS country_count,
          (SELECT count(*) FROM movie_people mp WHERE mp.movie_id = m.id) AS people_count,
          (SELECT count(*) FROM movie_ott_providers mop WHERE mop.movie_id = m.id) AS ott_count
        FROM movies m
        WHERE m.tmdb_id IN ($sample_ids)
        ORDER BY m.id
      ) TO STDOUT WITH CSV HEADER" \
    >"$sample_tmp" || { sample_error='QUERY_FAILED'; return 1; }
  candidate_rows="$(awk 'NR > 1 { count++ } END { print count + 0 }' "$sample_tmp")"
  atomic_replace "$sample_tmp" "$output_dir/postgres-sample.csv" \
    || { sample_error='PUBLISH_FAILED'; return 1; }
  sample_rows="$candidate_rows"
}

if [[ "$dry_run" == 'false' ]]; then
  if export_service_movies; then export_state='SUCCESS'; else export_state='FAILED'; fi
  if export_postgres_sample; then sample_state='SUCCESS'; else sample_state='FAILED'; fi
fi

run_status='SUCCESS'
if [[ "$failed" -ne 0 || "$export_state" == 'FAILED' || "$sample_state" == 'FAILED' ]]; then
  run_status='FAILED'
fi

manifest_tmp="$work_dir/run-manifest.json"
{
  printf '{\n'
  printf '  "status": "%s",\n' "$run_status"
  printf '  "input_file": "%s",\n' "$(json_escape "$(basename "$input")")"
  printf '  "input_bytes": %d,\n' "$input_bytes"
  printf '  "input_sha256": "%s",\n' "$input_sha256"
  printf '  "input_ids": %d,\n' "$input_count"
  printf '  "postgresql": {"status":"%s","inserted":%d,"updated":%d,"skipped":%d,"succeeded":%d,"failed":%d},\n' \
    "$([[ "$failed" -eq 0 ]] && printf SUCCESS || printf PARTIAL_FAILURE)" \
    "$inserted" "$updated" "$skipped" "$succeeded" "$failed"
  printf '  "unknown_ott_provider_occurrences": %d,\n' "$unknown_ott"
  printf '  "service_movie_export": {"status":"%s","reason":%s},\n' \
    "$export_state" "$([[ -z "$export_error" ]] && printf null || printf '"%s"' "$export_error")"
  printf '  "postgres_sample": {"status":"%s","reason":%s},\n' \
    "$sample_state" "$([[ -z "$sample_error" ]] && printf null || printf '"%s"' "$sample_error")"
  printf '  "service_movie_export_rows": %s,\n' "$export_rows"
  printf '  "postgres_sample_rows": %s,\n' "$sample_rows"
  if [[ "$export_sha256" == 'null' ]]; then
    printf '  "service_movie_export_sha256": null,\n'
  else
    printf '  "service_movie_export_sha256": "%s",\n' "$export_sha256"
  fi
  printf '  "hdfs": {"status":"NOT_RUN_BY_POSTGRES_SCRIPT"}\n'
  printf '}\n'
} >"$manifest_tmp"
atomic_replace "$manifest_tmp" "$output_dir/run-manifest.json" \
  || fail 'cannot publish run manifest'

{
  printf 'Run: %s\n' "$run_status"
  printf 'PostgreSQL: input=%d success=%d inserted=%d updated=%d skipped=%d failed=%d\n' \
    "$input_count" "$succeeded" "$inserted" "$updated" "$skipped" "$failed"
  printf 'Service movie export: %s reason=%s\n' "$export_state" "${export_error:-NONE}"
  printf 'PostgreSQL sample: %s reason=%s\n' "$sample_state" "${sample_error:-NONE}"
  printf 'HDFS: NOT_RUN_BY_POSTGRES_SCRIPT\n'
} >"$work_dir/run-summary.txt"
atomic_replace "$work_dir/run-summary.txt" "$output_dir/run-summary.txt" \
  || fail 'cannot publish run summary'
cat "$output_dir/run-summary.txt"

[[ "$run_status" == 'SUCCESS' ]] || exit 3
