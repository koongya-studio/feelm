#!/usr/bin/env bash
set -euo pipefail

# Uses real PostgreSQL, no external TMDb requests, host mounts or published ports.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
subject="$script_dir/../run-tmdb-ingestion.sh"
tmp="$(mktemp -d)"
container=''
cleanup() {
  [[ -z "$container" ]] || docker rm -f "$container" >/dev/null
  rm -rf -- "$tmp"
}
trap cleanup EXIT
container="$(docker run -d --rm --network none \
  -e POSTGRES_USER=postgres -e POSTGRES_DB=postgres \
  -e POSTGRES_HOST_AUTH_METHOD=trust pgvector/pgvector:0.8.6-pg17-bookworm)"
for ((i=0; i<60; i++)); do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
db() { docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres "$@"; }
cat "$script_dir"/../../src/main/resources/db/migration/*.sql | db -q >/dev/null
mkdir "$tmp/raw"
printf '550\n' >"$tmp/ids"
cat >"$tmp/raw/550.json" <<'JSON'
{"details":{"id":550,"title":"Catalog fixture","genres":[],"production_countries":[]},"credits":{"id":550,"cast":[],"crew":[]},"providers":{"id":550,"results":{}}}
JSON
header='tmdb_provider_id,code,name,logo_path'
tests=0
failures=0
run_no=0
run_catalog() {
  run_no=$((run_no + 1))
  status=0
  bash "$subject" --input "$tmp/ids" --output-dir "$tmp/output-$run_no" \
    --raw-dir "$tmp/raw" --postgres-container "$container" \
    --ott-mapping "$1" >"$tmp/run-$run_no.log" 2>&1 || status=$?
}
check() {
  tests=$((tests + 1))
  if [[ "$2" == "$3" ]]; then
    printf 'PASS %s\n' "$1"
  else
    failures=$((failures + 1))
    printf 'FAIL %s: expected=%s actual=%s\n' "$1" "$3" "$2"
  fi
}
snapshot() {
  db -qAt <<'SQL'
SELECT json_build_array(
  (SELECT json_agg(t ORDER BY id) FROM otts t),
  (SELECT json_agg(t ORDER BY user_id,ott_id) FROM user_otts t),
  (SELECT json_agg(t ORDER BY movie_id,ott_id) FROM movie_ott_providers t),
  (SELECT json_agg(t ORDER BY id) FROM watch_records t));
SQL
}
reset_fixture() {
  db -q <<'SQL' >/dev/null 2>&1
TRUNCATE TABLE users, movies, otts RESTART IDENTITY CASCADE;
INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id)
VALUES (42,'NETFLIX','Existing Netflix','/original.png',8);
INSERT INTO users(id,email,nickname,birth_date,gender,status)
VALUES (700,'catalog@example.invalid','catalog','2000-01-01','OTHER','ACTIVE');
INSERT INTO movies(id,tmdb_id,title) VALUES (900,900,'Existing movie');
INSERT INTO user_otts(user_id,ott_id) VALUES (700,42);
INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (900,42);
INSERT INTO watch_records(user_id,movie_id,ott_id,status)
VALUES (700,900,42,'WATCHED_UNRATED');
SQL
}

# Flyway SQL now bootstraps the approved five before any movie ingestion.
check 'fresh migration prepares five providers' "$(db -qAt -c 'SELECT count(*) FROM otts')" 5
before="$(snapshot)"
run_catalog "$script_dir/../samples/ott-provider-mapping.csv"
check 'ingestion immediately after seed exit' "$status" 0
check 'ingestion preserves seeded catalog IDs and metadata' "$(snapshot)" "$before"
for scenario in malformed provider-conflict code-conflict duplicate-provider duplicate-code overflow extra-column blank-name; do
  reset_fixture
  before="$(snapshot)"
  printf '%s\n337,DISNEY_PLUS,New Disney,/disney.png\n' "$header" >"$tmp/input.csv"
  case "$scenario" in
    malformed) printf '97,WATCHA,Bad logo,invalid\n' >>"$tmp/input.csv" ;;
    provider-conflict) printf '8,REASSIGNED,Wrong identity,/bad.png\n' >>"$tmp/input.csv" ;;
    code-conflict) printf '999,NETFLIX,Wrong provider,/bad.png\n' >>"$tmp/input.csv" ;;
    duplicate-provider) printf '337,ANOTHER,Repeated provider,/bad.png\n' >>"$tmp/input.csv" ;;
    duplicate-code) printf '999,DISNEY_PLUS,Repeated code,/bad.png\n' >>"$tmp/input.csv" ;;
    overflow) printf '2147483648,TOO_LARGE,Overflow,/bad.png\n' >>"$tmp/input.csv" ;;
    extra-column) printf '97,WATCHA,Extra comma,/valid.png,\n' >>"$tmp/input.csv" ;;
    blank-name) printf '97,WATCHA,   ,/valid.png\n' >>"$tmp/input.csv" ;;
  esac
  run_catalog "$tmp/input.csv"
  check "$scenario exit" "$status" 2
  check "$scenario atomic preservation" "$(snapshot)" "$before"
  case "$scenario" in
    duplicate-code|duplicate-provider|overflow|extra-column|blank-name)
      dry_status=0
      bash "$subject" --input "$tmp/ids" --output-dir "$tmp/dry-$scenario" \
        --raw-dir "$tmp/raw" --ott-mapping "$tmp/input.csv" --dry-run \
        >"$tmp/dry-$scenario.log" 2>&1 || dry_status=$?
      check "$scenario dry-run rejects malformed input" "$dry_status" 2
      ;;
  esac
done

reset_fixture
printf '%s\n8,NETFLIX,Existing Netflix,/original.png\n337,DISNEY_PLUS,Disney,/disney.png' "$header" >"$tmp/no-newline.csv"
run_catalog "$tmp/no-newline.csv"
check 'unterminated final CSV row exit' "$status" 0
check 'unterminated final CSV row imported' "$(db -qAt -c "SELECT count(*) FROM otts WHERE code='DISNEY_PLUS'")" 1
check 'existing service ID differs from provider and survives' "$(db -qAt -c "SELECT id FROM otts WHERE code='NETFLIX' AND tmdb_provider_id=8")" 42
check 'all three existing references survive' "$(db -qAt -c 'SELECT (SELECT count(*) FROM user_otts WHERE ott_id=42)+(SELECT count(*) FROM movie_ott_providers WHERE ott_id=42)+(SELECT count(*) FROM watch_records WHERE ott_id=42)')" 3
before="$(snapshot)"
run_catalog "$tmp/no-newline.csv"
check 'repeat exit' "$status" 0
check 'repeat catalog and references unchanged' "$(snapshot)" "$before"

# A server-side failure after an update and a successful insert must roll both back.
reset_fixture
before="$(snapshot)"
db -q <<'SQL' >/dev/null
CREATE FUNCTION reject_catalog_test_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.code = 'WATCHA' THEN RAISE EXCEPTION 'injected catalog write failure'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER reject_catalog_test_row BEFORE INSERT ON otts
FOR EACH ROW EXECUTE FUNCTION reject_catalog_test_row();
SQL
run_catalog "$script_dir/../samples/ott-provider-mapping.csv"
check 'injected DB error exit' "$status" 2
check 'injected DB error rolls back every catalog row' "$(snapshot)" "$before"
db -q -c 'DROP TRIGGER reject_catalog_test_row ON otts; DROP FUNCTION reject_catalog_test_row();'
run_catalog "$script_dir/../samples/ott-provider-mapping.csv"
check 'retry after removing failure cause exit' "$status" 0
check 'approved CSV prepares five rows' "$(db -qAt -c 'SELECT count(*) FROM otts')" 5
check 'existing Netflix ID retained by approved CSV' "$(db -qAt -c "SELECT id FROM otts WHERE code='NETFLIX'")" 42

# Upgrade a catalog populated by the actual loader, then run ingestion again.
before="$(snapshot)"
db -q <"$script_dir/../../src/main/resources/db/migration/V202609142240__seed_approved_ott_catalog.sql"
check 'seed after actual ingestion preserves every catalog row and reference' "$(snapshot)" "$before"
run_catalog "$script_dir/../samples/ott-provider-mapping.csv"
check 'ingestion after seed upgrade exit' "$status" 0
check 'ingestion seed ingestion sequence preserves identities and references' "$(snapshot)" "$before"

# Two loaders are queued behind the same catalog writer. Subscription writes can
# still lock their user and reference an existing OTT while this lock is held.
reset_fixture
db -q -c 'BEGIN; LOCK TABLE otts IN SHARE ROW EXCLUSIVE MODE; SELECT pg_sleep(6); COMMIT;' >"$tmp/blocker.log" 2>&1 &
blocker=$!
for ((i=0; i<100; i++)); do
  [[ "$(db -qAt -c "SELECT count(*) FROM pg_locks WHERE relation='otts'::regclass AND mode='ShareRowExclusiveLock' AND granted")" == 1 ]] && break
  sleep 0.1
done
check 'concurrency blocker acquired' "$((i < 100))" 1
for concurrent in 1 2; do
  (
    result=0
    bash "$subject" --input "$tmp/ids" --output-dir "$tmp/concurrent-$concurrent" \
      --raw-dir "$tmp/raw" --postgres-container "$container" \
      --ott-mapping "$script_dir/../samples/ott-provider-mapping.csv" \
      >"$tmp/concurrent-$concurrent.log" 2>&1 || result=$?
    printf '%s' "$result" >"$tmp/concurrent-$concurrent.status"
  ) &
done
db -q <<'SQL' >"$tmp/subscription.log" 2>&1
BEGIN;
SET LOCAL lock_timeout='2s';
SELECT id FROM users WHERE id=700 FOR UPDATE;
DELETE FROM user_otts WHERE user_id=700;
INSERT INTO user_otts(user_id,ott_id) VALUES(700,42);
COMMIT;
SQL
check 'subscription completes while catalog lock held' "$(db -qAt -c "SELECT count(*) FROM pg_locks WHERE relation='otts'::regclass AND mode='ShareRowExclusiveLock' AND granted")" 1
wait "$blocker"
wait
check 'concurrent loader 1 exit' "$(cat "$tmp/concurrent-1.status")" 0
check 'concurrent loader 2 exit' "$(cat "$tmp/concurrent-2.status")" 0
check 'concurrent loaders produce five identities' "$(db -qAt -c 'SELECT count(*) FROM otts')" 5
check 'concurrent subscription/movie/history references survive' "$(db -qAt -c 'SELECT (SELECT count(*) FROM user_otts WHERE ott_id=42)+(SELECT count(*) FROM movie_ott_providers WHERE ott_id=42)+(SELECT count(*) FROM watch_records WHERE ott_id=42)')" 3

# Every FK continues to forbid physical removal. No constraints are disabled.
check 'complete FK reference inventory' "$(db -qAt -c "SELECT string_agg(conrelid::regclass::text || ':' || confdeltype::text,',' ORDER BY conrelid::regclass::text) FROM pg_constraint WHERE contype='f' AND confrelid='otts'::regclass")" 'movie_ott_providers:r,user_otts:r,watch_records:r'
before="$(snapshot)"
delete_status=0
db -q -c 'DELETE FROM otts WHERE id=42' >"$tmp/delete.log" 2>&1 || delete_status=$?
check 'referenced catalog deletion remains rejected' "$delete_status" 1
check 'rejected deletion preserves data' "$(snapshot)" "$before"

# Run the exact documented read-only inspection command against this test container.
OTT_POSTGRES_CONTAINER="$container"
docker exec -i "$OTT_POSTGRES_CONTAINER" sh -c \
  'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  <"$script_dir/../inspect-ott-catalog.sql" >"$tmp/inspection.log"
check 'documented inspection preserves data' "$(snapshot)" "$before"

# Removing only the final LF must not remove a provider from movie synchronization.
head -c -1 "$script_dir/../samples/ott-provider-mapping.csv" >"$tmp/five-no-newline.csv"
jq '.providers.results = {"KR":{"flatrate":[{"provider_id":1883}]}}' \
  "$tmp/raw/550.json" >"$tmp/raw-updated.json"
mv "$tmp/raw-updated.json" "$tmp/raw/550.json"
run_catalog "$tmp/five-no-newline.csv"
check 'full CSV without final LF exit' "$status" 0
check 'final provider remains in movie relation' "$(db -qAt -c 'SELECT o.code FROM movie_ott_providers p JOIN otts o ON o.id=p.ott_id JOIN movies m ON m.id=p.movie_id WHERE m.tmdb_id=550')" TVING

printf 'Tests=%s Failures=%s Errors=0 Skipped=0\n' "$tests" "$failures"
[[ "$failures" == 0 ]]
