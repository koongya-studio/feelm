#!/usr/bin/env bash
set -euo pipefail

# Exercise the real ingestion payload without a database or TMDb network access.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT
mkdir -p "$test_dir/bin" "$test_dir/raw"
printf '2\n' >"$test_dir/ids.txt"
export PAYLOAD_FILE="$test_dir/payload.json"
export PGHOST='unused.invalid' PGPORT=5432 PGDATABASE=postgres PGUSER=postgres
export PGPASSWORD='test-only' PATH="$test_dir/bin:$PATH"

cat >"$test_dir/bin/psql" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
sql=''
while (($#)); do
  if [[ "$1" == '-c' ]]; then sql="$2"; break; fi
  shift
done
if [[ -z "$sql" ]]; then sql="$(cat)"; fi
case "$sql" in
  *'SELECT 1;'*) printf '1\n' ;;
  *"to_regclass('public.movies')"*) printf '1\n' ;;
  *'SELECT EXISTS (SELECT 1 FROM movies'*) printf 'f\n' ;;
  *'INSERT INTO _tmdb_payload'*)
    encoded="$(printf '%s\n' "$sql" | sed -n "s/.*VALUES (convert_from(decode('\\([^']*\\)', 'base64').*/\\1/p")"
    [[ -n "$encoded" ]]
    printf '%s' "$encoded" | base64 -d >"$PAYLOAD_FILE"
    ;;
  *'COPY (SELECT id AS service_movie_id'*) printf 'service_movie_id,tmdb_id\n1,2\n' ;;
  *'COPY ('*) printf 'service_movie_id,tmdb_id,title,genre_count,country_count,people_count,ott_count\n' ;;
  *) printf 'unexpected SQL in title test\n' >&2; exit 1 ;;
esac
SH
chmod +x "$test_dir/bin/psql"

cat >"$test_dir/base.json" <<'JSON'
{
  "details":{"id":2,"title":"Foreign Title","original_title":"Original Title","genres":[],"production_countries":[]},
  "credits":{"id":2,"cast":[],"crew":[]},
  "providers":{"id":2,"results":{"KR":{"flatrate":[]}}}
}
JSON

check_title() {
  local name="$1" expected="$2" expected_original="${3:-Original Title}"
  bash "$script_dir/../run-tmdb-ingestion.sh" \
    --input "$test_dir/ids.txt" --raw-dir "$test_dir/raw" \
    --output-dir "$test_dir/$name" --postgres-mode direct >/dev/null
  jq -e --arg title "$expected" --arg original "$expected_original" \
    '.title == $title and .original_title == $original' \
    "$PAYLOAD_FILE" >/dev/null
}

jq '.details.translations = {translations:[
      {iso_639_1:"ko",iso_3166_1:"KR",data:{title:"한국 번역 제목"}}]}' \
  "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title translation '한국 번역 제목'

jq '.details.title = "" | .details.translations = {translations:[
      {iso_639_1:"ko",iso_3166_1:"KR",data:{title:"첫 번째 번역"}},
      {iso_639_1:"ko",iso_3166_1:"KR",data:{title:"두 번째 번역"}}]}' \
  "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title ambiguous_translation 'Original Title'

jq '.details.alternative_titles = {titles:[
      {iso_3166_1:"KR",title:"한국 대체 제목"}]}' \
  "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title alternative '한국 대체 제목'

jq '.details.alternative_titles = {titles:[
      {iso_3166_1:"KR",title:"첫 번째 제목"},
      {iso_3166_1:"KR",title:"두 번째 제목"}]}' \
  "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title ambiguous 'Foreign Title'

jq '.details.title = "" | .details.alternative_titles = {titles:[
      {iso_3166_1:"KR",title:"첫 번째 제목"},
      {iso_3166_1:"KR",title:"두 번째 제목"}]}' \
  "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title empty_ambiguous 'Original Title'

jq '.details.title = "한국 상세 제목"' "$test_dir/base.json" >"$test_dir/raw/2.json"
check_title localized '한국 상세 제목'

cp "$test_dir/base.json" "$test_dir/raw/2.json"
check_title fallback 'Foreign Title'

printf '139\n' >"$test_dir/ids.txt"
jq '.details.id = 139 | .credits.id = 139 | .providers.id = 139
    | .details.title = "Mifunes sidste sang"
    | .details.original_title = "Mifunes sidste sang"
    | .details.release_date = "1999-03-12"' \
  "$test_dir/base.json" >"$test_dir/raw/139.json"
check_title verified_mifune '미후네' 'Mifunes sidste sang'

printf '2111\n' >"$test_dir/ids.txt"
jq '.details.id = 2111 | .credits.id = 2111 | .providers.id = 2111
    | .details.title = "巴尔扎克与小裁缝"
    | .details.original_title = "巴尔扎克与小裁缝"
    | .details.release_date = "2002-05-16"' \
  "$test_dir/base.json" >"$test_dir/raw/2111.json"
check_title verified_balzac '발자크와 바느질하는 중국 소녀' '巴尔扎克与小裁缝'

printf '6519\n' >"$test_dir/ids.txt"
jq '.details.id = 6519 | .credits.id = 6519 | .providers.id = 6519
    | .details.title = "The Dukes of Hazzard"
    | .details.original_title = "The Dukes of Hazzard"
    | .details.release_date = "2005-08-05"' \
  "$test_dir/base.json" >"$test_dir/raw/6519.json"
check_title verified_korean_title '듀크 오브 해저드' 'The Dukes of Hazzard'

jq '.details.title = "TMDb 한국어 제목"' "$test_dir/raw/6519.json" \
  >"$test_dir/tmdb-ko.json"
cp "$test_dir/tmdb-ko.json" "$test_dir/raw/6519.json"
check_title tmdb_title_precedence 'TMDb 한국어 제목' 'The Dukes of Hazzard'

jq '.details.original_title = "Another film"' "$test_dir/raw/6519.json" \
  >"$test_dir/wrong-identity.json"
cp "$test_dir/wrong-identity.json" "$test_dir/raw/6519.json"
if bash "$script_dir/../run-tmdb-ingestion.sh" \
    --input "$test_dir/ids.txt" --raw-dir "$test_dir/raw" \
    --output-dir "$test_dir/wrong_identity" --postgres-mode direct >/dev/null; then
  printf 'curated title must reject a mismatched movie identity\n' >&2
  exit 1
fi
jq -e '.[0].tmdb_id == 6519 and
       .[0].reason == "TITLE_OVERRIDE_IDENTITY_MISMATCH"' \
  "$test_dir/wrong_identity/failure-report.json" >/dev/null

printf '13284\n' >"$test_dir/ids.txt"
jq '.details.id = 13284 | .credits.id = 13284 | .providers.id = 13284
    | .details.title = "Barbie: Fairytopia"
    | .details.original_title = "Barbie: Fairytopia"
    | .details.release_date = "2005-03-08"' \
  "$test_dir/base.json" >"$test_dir/raw/13284.json"
check_title verified_barbie '바비: 페어리토피아' 'Barbie: Fairytopia'

printf '25846\n' >"$test_dir/ids.txt"
jq '.details.id = 25846 | .credits.id = 25846 | .providers.id = 25846
    | .details.title = "Baarìa"
    | .details.original_title = "Baarìa"
    | .details.release_date = "2009-09-25"' \
  "$test_dir/base.json" >"$test_dir/raw/25846.json"
check_title verified_baaria '바리아' 'Baarìa'

printf 'TMDb title selection tests passed\n'
