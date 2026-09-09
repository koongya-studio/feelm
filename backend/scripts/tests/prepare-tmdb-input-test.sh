#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
subject="$script_dir/../prepare-tmdb-input.sh"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT

cat >"$test_dir/links.csv" <<'CSV'
movieId,imdbId,tmdbId
1,0114709,862
2,0113497,8844
3,0113228,
4,0114885,862
5,0113041,15602
CSV

cat >"$test_dir/ssafy.csv" <<'CSV'
id|title_en|title_ko
8844|Jumanji|쥬만지
99999|Sample|샘플
CSV

bash "$subject" \
  --movielens-links "$test_dir/links.csv" \
  --ssafy-movies "$test_dir/ssafy.csv" \
  --scope movielens-ml-32m-plus-ssafy \
  --sample-size 3 \
  --output-dir "$test_dir/output"

diff -u <(printf '862\n8844\n15602\n99999\n') "$test_dir/output/tmdb-ids.txt"
diff -u <(printf '862\n8844\n99999\n') "$test_dir/output/tmdb-ids-sample.txt"
diff -u <(printf 'tmdb_id,in_movielens,in_ssafy\n862,true,false\n8844,true,true\n15602,true,false\n99999,false,true\n') \
  "$test_dir/output/tmdb-id-sources.csv"

manifest="$test_dir/output/tmdb-input-manifest.json"
grep -F '"status": "PREPARED_NOT_INGESTED"' "$manifest" >/dev/null
grep -F '"scope": "movielens-ml-32m-plus-ssafy"' "$manifest" >/dev/null
grep -F '"movielens_rows": 5' "$manifest" >/dev/null
grep -F '"movielens_with_tmdb_id": 4' "$manifest" >/dev/null
grep -F '"movielens_missing_tmdb_id": 1' "$manifest" >/dev/null
grep -F '"movielens_unique_tmdb_ids": 3' "$manifest" >/dev/null
grep -F '"movielens_duplicate_tmdb_rows": 1' "$manifest" >/dev/null
grep -F '"ssafy_rows": 2' "$manifest" >/dev/null
grep -F '"ssafy_unique_tmdb_ids": 2' "$manifest" >/dev/null
grep -F '"source_overlap_tmdb_ids": 1' "$manifest" >/dev/null
grep -F '"final_unique_tmdb_ids": 4' "$manifest" >/dev/null
grep -F '"sample_tmdb_ids": 3' "$manifest" >/dev/null
grep -E '"tmdb_ids_sha256": "[0-9a-f]{64}"' "$manifest" >/dev/null

expected="$test_dir/output/sample-expected.json"
grep -F '"input": "tmdb-ids-sample.txt"' "$expected" >/dev/null
grep -F '"input_ids": 3' "$expected" >/dev/null
grep -F '"postgresql_status": "SUCCESS"' "$expected" >/dev/null
grep -F '"failed": 0' "$expected" >/dev/null
grep -F '"rerun_skips": 3' "$expected" >/dev/null
grep -F '"service_movie_csv_header": "service_movie_id,tmdb_id"' "$expected" >/dev/null

if bash "$subject" \
    --movielens-links "$test_dir/links.csv" \
    --scope invalid-test \
    --sample-size 0 \
    --output-dir "$test_dir/invalid-output" >/dev/null 2>&1; then
  printf 'expected invalid sample size to fail\n' >&2
  exit 1
fi

cat >"$test_dir/links-without-tmdb.csv" <<'CSV'
movieId,imdbId,tmdbId
1,0114709,
CSV
bash "$subject" \
  --movielens-links "$test_dir/links-without-tmdb.csv" \
  --ssafy-movies "$test_dir/ssafy.csv" \
  --scope ssafy-fallback \
  --sample-size 5 \
  --output-dir "$test_dir/ssafy-fallback-output" >/dev/null
diff -u <(printf '8844\n99999\n') "$test_dir/ssafy-fallback-output/tmdb-ids.txt"

printf 'prepare-tmdb-input tests passed\n'
