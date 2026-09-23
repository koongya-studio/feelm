#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hdfs_subject="$script_dir/../run-initial-data-load.sh"
postgres_subject="$script_dir/../run-tmdb-ingestion.sh"
approved_people_subject="$script_dir/../data/approved-foreign-person-original-names.tsv"
original_name_extractor="$script_dir/../extract-people-original-name.py"
readme_subject="$script_dir/../../README.md"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT

if command -v python3 >/dev/null 2>&1; then
  python_command=python3
elif command -v python >/dev/null 2>&1; then
  python_command=python
else
  printf 'python is required to test the original-name extractor\n' >&2
  exit 1
fi

printf 'alpha\n' >"$test_dir/ratings.csv"
printf 'beta\n' >"$test_dir/links.csv"
printf 'gamma\n' >"$test_dir/movies.csv"
printf 'delta\n' >"$test_dir/tags.csv"
printf 'readme\n' >"$test_dir/README.txt"
printf 'checksums\n' >"$test_dir/checksums.txt"

bash "$hdfs_subject" manifest \
  --dataset movielens \
  --version ml-32m \
  --scope full-official-release \
  --output-dir "$test_dir/hdfs-output" \
  --file "$test_dir/ratings.csv" \
  --file "$test_dir/links.csv" \
  --file "$test_dir/movies.csv" \
  --file "$test_dir/tags.csv" \
  --file "$test_dir/README.txt" \
  --file "$test_dir/checksums.txt"

hdfs_manifest="$test_dir/hdfs-output/hdfs-manifest.json"
grep -F '"status": "PREPARED_NOT_UPLOADED"' "$hdfs_manifest" >/dev/null
grep -F '"hdfs_target": "/data/raw/movielens/ml-32m"' "$hdfs_manifest" >/dev/null
grep -F '"duplicate_policy": "skip-if-same"' "$hdfs_manifest" >/dev/null
grep -F '"file_count": 6' "$hdfs_manifest" >/dev/null
for expected_file in links.csv movies.csv ratings.csv tags.csv README.txt checksums.txt; do
  grep -F "\"name\":\"$expected_file\"" "$hdfs_manifest" >/dev/null
done
grep -E '"sha256":"[0-9a-f]{64}"' "$hdfs_manifest" >/dev/null

if bash "$hdfs_subject" manifest \
    --dataset movielens \
    --version ml-32m \
    --scope full-official-release \
    --output-dir "$test_dir/incomplete-hdfs-output" \
    --file "$test_dir/ratings.csv" \
    --file "$test_dir/links.csv" \
    --file "$test_dir/movies.csv" \
    --file "$test_dir/tags.csv" >/dev/null 2>&1; then
  printf 'expected incomplete MovieLens full release to fail validation\n' >&2
  exit 1
fi

printf '2\n3\n5\n6\n11\n' >"$test_dir/tmdb-ids.txt"
bash "$postgres_subject" \
  --input "$test_dir/tmdb-ids.txt" \
  --output-dir "$test_dir/postgres-output" \
  --limit 3 \
  --validate-only

input_manifest="$test_dir/postgres-output/input-manifest.json"
grep -F '"file_name": "tmdb-ids.txt"' "$input_manifest" >/dev/null
grep -F '"selected_tmdb_ids": 3' "$input_manifest" >/dev/null
grep -E '"sha256": "[0-9a-f]{64}"' "$input_manifest" >/dev/null

printf '2\n2\n' >"$test_dir/duplicate-ids.txt"
if bash "$postgres_subject" \
    --input "$test_dir/duplicate-ids.txt" \
    --output-dir "$test_dir/duplicate-output" \
    --validate-only >/dev/null 2>&1; then
  printf 'expected duplicate TMDb IDs to fail validation\n' >&2
  exit 1
fi

if grep -Eq 'hdfs namenode -format|docker compose down -v' \
    "$hdfs_subject" "$postgres_subject"; then
  printf 'destructive infrastructure command found in load scripts\n' >&2
  exit 1
fi

grep -F 'Approved CSV with exact header: tmdb_provider_id,code,name,logo_path' \
  "$postgres_subject" >/dev/null
grep -F 'providers: ([($p.results.KR.flatrate // [])[] |' "$postgres_subject" >/dev/null
if grep -F '["flatrate","free","ads","rent","buy"]' "$postgres_subject" >/dev/null; then
  printf 'non-subscription TMDb offer types must not be collected\n' >&2
  exit 1
fi
grep -F 'INSERT INTO otts (code, name, logo_url, tmdb_provider_id)' \
  "$postgres_subject" >/dev/null
grep -F 'GROUP BY tmdb_person_id' "$postgres_subject" >/dev/null
grep -F 'declare -A approved_foreign_person_names=()' "$postgres_subject" >/dev/null
grep -F -- '--rawfile approved_person_names "$current_approved_names"' "$postgres_subject" >/dev/null
grep -F 'CREATE TEMP TABLE _approved_person_names' "$postgres_subject" >/dev/null
grep -F 'cannot reconcile people.original_name with approved data' "$postgres_subject" >/dev/null
if grep -F $'^2027339\t' "$approved_people_subject" >/dev/null; then
  printf 'reviewed Korean person 준회 must not be approved as foreign\n' >&2
  exit 1
fi
grep -Fx $'64707\tYu Chenghui' "$approved_people_subject" >/dev/null
grep -Fx $'77271\tP!nk' "$approved_people_subject" >/dev/null
grep -Fx $'107730\tTrần Anh Hùng' "$approved_people_subject" >/dev/null
if grep -F "person->>'name' ~" "$postgres_subject" >/dev/null; then
  printf 'person nationality must come from reviewed TMDb person-name pairs, not a name regex\n' >&2
  exit 1
fi

mkdir -p "$test_dir/original-name-raw"
printf '1\n' >"$test_dir/original-name-movie-ids.txt"
cat >"$test_dir/original-name-raw/1.json" <<'JSON'
{"details":{"id":1},"credits":{"crew":[{"id":107730,"name":"트란 안 홍","original_name":"Trần Anh Hùng","job":"Director"}],"cast":[{"id":2027339,"name":"준회","original_name":"Ju-ne"},{"id":64707,"name":"우승혜","original_name":"Yu Chenghui"},{"id":77271,"name":"핑크","original_name":"P!nk"}]}}
JSON
"$python_command" "$original_name_extractor" \
  --raw-dir "$test_dir/original-name-raw" \
  --expected-movie-ids "$test_dir/original-name-movie-ids.txt" \
  --expected-file-count 1 \
  --expected-selected-count 3 \
  --output "$test_dir/original-names.tsv" >/dev/null
if grep -F $'2027339\t' "$test_dir/original-names.tsv" >/dev/null; then
  printf 'reviewed Korean person 준회 must not receive a Latin display name\n' >&2
  exit 1
fi
grep -Fx $'64707\tYu Chenghui' "$test_dir/original-names.tsv" >/dev/null
grep -Fx $'77271\tP!nk' "$test_dir/original-names.tsv" >/dev/null
grep -Fx $'107730\tTrần Anh Hùng' "$test_dir/original-names.tsv" >/dev/null
if "$python_command" "$original_name_extractor" \
    --raw-dir "$test_dir/original-name-raw" \
    --expected-movie-ids "$test_dir/original-name-movie-ids.txt" \
    --expected-file-count 2 \
    --expected-selected-count 3 \
    --output "$test_dir/incomplete-original-names.tsv" >/dev/null 2>&1; then
  printf 'expected incomplete TMDb raw directory to fail validation\n' >&2
  exit 1
fi
mkdir -p "$test_dir/conflicting-original-name-raw"
cat >"$test_dir/conflicting-original-name-raw/1.json" <<'JSON'
{"details":{"id":1},"credits":{"crew":[],"cast":[{"id":64707,"name":"우승혜","original_name":"Yu Chenghui"}]}}
JSON
cat >"$test_dir/conflicting-original-name-raw/2.json" <<'JSON'
{"details":{"id":2},"credits":{"crew":[],"cast":[{"id":64707,"name":"우승혜","original_name":"Yu Cheng-hui"}]}}
JSON
printf '1\n2\n' >"$test_dir/conflicting-original-name-movie-ids.txt"
if "$python_command" "$original_name_extractor" \
    --raw-dir "$test_dir/conflicting-original-name-raw" \
    --expected-movie-ids "$test_dir/conflicting-original-name-movie-ids.txt" \
    --expected-file-count 2 \
    --expected-selected-count 1 \
    --output "$test_dir/conflicting-original-names.tsv" >/dev/null 2>&1; then
  printf 'expected conflicting original names for one TMDb person to fail validation\n' >&2
  exit 1
fi

mkdir -p "$test_dir/missing-approved-original-name-raw"
cat >"$test_dir/missing-approved-original-name-raw/1.json" <<'JSON'
{"details":{"id":1},"credits":{"crew":[],"cast":[{"id":64707,"name":"우승혜","original_name":null}]}}
JSON
if "$python_command" "$original_name_extractor" \
    --raw-dir "$test_dir/missing-approved-original-name-raw" \
    --expected-movie-ids "$test_dir/original-name-movie-ids.txt" \
    --expected-file-count 1 \
    --expected-selected-count 1 \
    --output "$test_dir/missing-approved-original-names.tsv" >/dev/null 2>&1; then
  printf 'expected missing original name for an approved person to fail validation\n' >&2
  exit 1
fi

mkdir -p "$test_dir/substituted-original-name-raw"
cp "$test_dir/original-name-raw/1.json" "$test_dir/substituted-original-name-raw/2.json"
if "$python_command" "$original_name_extractor" \
    --raw-dir "$test_dir/substituted-original-name-raw" \
    --expected-movie-ids "$test_dir/original-name-movie-ids.txt" \
    --expected-file-count 1 \
    --expected-selected-count 3 \
    --output "$test_dir/substituted-original-names.tsv" >/dev/null 2>&1; then
  printf 'expected same-count substituted TMDb payload set to fail validation\n' >&2
  exit 1
fi

grep -F 'genres: ([($d.genres // [])[] | select(.id > 0 and (.name | length) > 0) | {id,name}] | unique_by(.id))' \
  "$postgres_subject" >/dev/null
grep -F "GROUP BY (genre->>'id')::int" "$postgres_subject" >/dev/null
grep -F 'SELECT DISTINCT (SELECT id FROM _movie_id), genres.id' "$postgres_subject" >/dev/null
grep -F 'WHERE m.tmdb_id IN ($sample_ids)' "$postgres_subject" >/dev/null
grep -F 'TMDB_POSTER_IMAGE_BASE="${TMDB_POSTER_IMAGE_BASE:-https://image.tmdb.org/t/p/w500}"' \
  "$postgres_subject" >/dev/null
grep -F 'TMDB_BACKDROP_IMAGE_BASE="${TMDB_BACKDROP_IMAGE_BASE:-https://image.tmdb.org/t/p/w1280}"' \
  "$postgres_subject" >/dev/null
grep -F 'poster_url: (($d.poster_path // "") | if length > 0 then $poster_image_base + . else null end)' \
  "$postgres_subject" >/dev/null
grep -F 'backdrop_url: (($d.backdrop_path // "") | if length > 0 then $backdrop_image_base + . else null end)' \
  "$postgres_subject" >/dev/null
grep -F '영화별 제공처는 `.results.KR.flatrate`에서만 선택한다.' \
  "$readme_subject" >/dev/null
grep -F '포스터는 `w500`, 배경은 `w1280`' "$readme_subject" >/dev/null
grep -F '지역 단위 TMDb/JustWatch 안내 페이지' "$readme_subject" >/dev/null
grep -F '넷플릭스·디즈니+·왓챠·웨이브·티빙 5종이며' \
  "$readme_subject" >/dev/null
grep -F 'MovieLens 기본 입력은 원본 연결 범위를 보존' \
  "$readme_subject" >/dev/null
grep -F 'bash ./backend/scripts/collect-tmdb-keywords.sh' "$readme_subject" >/dev/null
grep -F '`PARTIAL` 상태도 성공 원본과' \
  "$readme_subject" >/dev/null
grep -F '줄거리 보강은 초기 적재의 완료 조건이 아닌 별도 데이터 품질 작업이다.' \
  "$readme_subject" >/dev/null
grep -F -- '--postgres-mode MODE      docker or direct' "$postgres_subject" >/dev/null
grep -F 'PGHOST, PGPORT, PGDATABASE and PGUSER' "$postgres_subject" >/dev/null
grep -F 'ssh -N -L 15432:127.0.0.1:5432' "$readme_subject" >/dev/null
grep -F -- '--postgres-mode direct' "$readme_subject" >/dev/null
grep -F 'unset PGPASSWORD TMDB_TOKEN' "$readme_subject" >/dev/null
if grep -Eq 'postgres(ql)?://[^[:space:]]+:[^[:space:]@]+@' \
    "$postgres_subject" "$readme_subject"; then
  printf 'database credentials must not be embedded in scripts or documentation\n' >&2
  exit 1
fi

if command -v jq >/dev/null 2>&1; then
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
: >"$header_file"
case "$url" in
  */credits*)
    printf '{"id":2,"crew":[],"cast":[]}' >"$output_file"
    ;;
  */watch/providers*)
    cat >"$output_file" <<'JSON'
{"id":2,"results":{"KR":{"link":"https://www.themoviedb.org/movie/2/watch?locale=KR","flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg"}],"rent":[{"provider_id":337,"provider_name":"Disney Plus","logo_path":"/disney.jpg"}],"free":[{"provider_id":97,"provider_name":"Watcha","logo_path":"/watcha.jpg"}]}}}
JSON
    ;;
  *)
    cat >"$output_file" <<'JSON'
{"id":2,"title":"Sample","original_title":"Sample","overview":"sample","release_date":"2020-01-01","runtime":90,"poster_path":null,"backdrop_path":null,"genres":[],"production_countries":[]}
JSON
    ;;
esac
printf '200'
SH
  chmod +x "$test_dir/fake-bin/curl"

  printf '2\n' >"$test_dir/flatrate-id.txt"
  cat >"$test_dir/ott-mapping.csv" <<'CSV'
tmdb_provider_id,code,name,logo_path
8,NETFLIX,넷플릭스,/netflix.jpg
CSV
  PATH="$test_dir/fake-bin:$PATH" \
  TMDB_TOKEN='test-token' \
  TMDB_API_BASE='https://example.invalid/3' \
  bash "$postgres_subject" \
    --input "$test_dir/flatrate-id.txt" \
    --output-dir "$test_dir/flatrate-output" \
    --ott-mapping "$test_dir/ott-mapping.csv" \
    --dry-run >/dev/null
  grep -F '"postgresql": {"status":"SUCCESS"' \
    "$test_dir/flatrate-output/run-manifest.json" >/dev/null
  grep -F '"unknown_ott_provider_occurrences": 0' \
    "$test_dir/flatrate-output/run-manifest.json" >/dev/null
fi

bash "$script_dir/tmdb-ingestion-regression-test.sh"

printf 'initial data load tests passed\n'
