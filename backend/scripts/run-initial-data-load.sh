#!/usr/bin/env bash
set -uo pipefail

readonly HDFS_CONTAINER='feelm-hdfs-namenode'

usage() {
  cat <<'USAGE'
Usage:
  run-initial-data-load.sh manifest [HDFS options]
  run-initial-data-load.sh validate [HDFS options]
  run-initial-data-load.sh hdfs [HDFS options]
  run-initial-data-load.sh postgres [run-tmdb-ingestion.sh options]

HDFS options:
  --dataset NAME             Dataset name recorded in the manifest
  --version VERSION          Immutable source version recorded in the manifest
  --scope SCOPE              Approved load scope identifier
  --hdfs-target PATH         Absolute HDFS directory (default: /data/raw/<dataset>/<version>)
  --duplicate-policy POLICY  fail, skip-if-same, or replace (default: skip-if-same)
  --output-dir DIR           Host directory for hdfs-manifest.json and summary
  --file FILE                Raw source file; repeat for every MovieLens/TMDb file

The validate and hdfs commands must run on J15E106A where feelm-hdfs-namenode
is running. The postgres command runs on J15E106. The manifest command only
inspects local files. HDFS and PostgreSQL are intentionally run separately.
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

command_name="${1:-}"
[[ -n "$command_name" ]] || { usage; exit 2; }
shift

if [[ "$command_name" == 'postgres' ]]; then
  exec bash "$(dirname "$0")/run-tmdb-ingestion.sh" "$@"
fi
if [[ "$command_name" == '--help' || "$command_name" == '-h' ]]; then
  usage
  exit 0
fi
[[ "$command_name" == 'manifest' || "$command_name" == 'validate' \
  || "$command_name" == 'hdfs' ]] \
  || fail "unknown command: $command_name"

dataset=''
version=''
scope=''
hdfs_target=''
duplicate_policy='skip-if-same'
output_dir=''
files=()
while (($#)); do
  case "$1" in
    --dataset) (($# >= 2)) || fail '--dataset requires a value'; dataset="$2"; shift 2 ;;
    --version) (($# >= 2)) || fail '--version requires a value'; version="$2"; shift 2 ;;
    --scope) (($# >= 2)) || fail '--scope requires a value'; scope="$2"; shift 2 ;;
    --hdfs-target) (($# >= 2)) || fail '--hdfs-target requires a value'; hdfs_target="$2"; shift 2 ;;
    --duplicate-policy) (($# >= 2)) || fail '--duplicate-policy requires a value'; duplicate_policy="$2"; shift 2 ;;
    --output-dir) (($# >= 2)) || fail '--output-dir requires a value'; output_dir="$2"; shift 2 ;;
    --file) (($# >= 2)) || fail '--file requires a value'; files+=("$2"); shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown HDFS argument: $1" ;;
  esac
done

require_command awk
require_command realpath
require_command sha256sum
require_command stat

[[ "$dataset" =~ ^[A-Za-z0-9._-]+$ ]] || fail '--dataset is required and must be path-safe'
[[ "$version" =~ ^[A-Za-z0-9._-]+$ ]] || fail '--version is required and must be path-safe'
[[ "$scope" =~ ^[A-Za-z0-9._-]+$ ]] || fail '--scope is required and must be path-safe'
hdfs_target="${hdfs_target:-/data/raw/$dataset/$version}"
[[ "$hdfs_target" == /* && "$hdfs_target" != '/' && "$hdfs_target" != *'..'* ]] \
  || fail '--hdfs-target must be an absolute non-root HDFS path without ..'
[[ "$duplicate_policy" == 'fail' || "$duplicate_policy" == 'skip-if-same' \
  || "$duplicate_policy" == 'replace' ]] || fail 'invalid duplicate policy'
[[ -n "$output_dir" ]] || fail '--output-dir is required'
((${#files[@]} > 0)) || fail 'at least one --file is required'

mkdir -p "$output_dir" || fail 'cannot create output directory'
output_dir="$(realpath "$output_dir")" || fail 'cannot resolve output directory'

declare -A seen_names=()
resolved_files=()
file_sizes=()
file_hashes=()
total_size=0
for file in "${files[@]}"; do
  [[ -r "$file" && -f "$file" ]] || fail "source file is not readable: $file"
  resolved="$(realpath "$file")" || fail "cannot resolve source file: $file"
  name="$(basename "$resolved")"
  [[ -z "${seen_names[$name]:-}" ]] || fail "duplicate source basename: $name"
  seen_names[$name]=1
  size="$(stat -c '%s' "$resolved")" || fail "cannot read source size: $name"
  hash="$(sha256sum "$resolved" | awk '{print $1}')" || fail "cannot hash source: $name"
  resolved_files+=("$resolved")
  file_sizes+=("$size")
  file_hashes+=("$hash")
  total_size=$((total_size + size))
done

if [[ "$dataset" == 'movielens' && "$version" == 'ml-32m' \
  && "$scope" == 'full-official-release' ]]; then
  movielens_required_files=(
    'links.csv'
    'movies.csv'
    'ratings.csv'
    'tags.csv'
    'README.txt'
    'checksums.txt'
  )
  ((${#resolved_files[@]} == ${#movielens_required_files[@]})) \
    || fail 'MovieLens ml-32m full-official-release requires exactly 6 original files (4 CSV + README.txt + checksums.txt)'
  for required_file in "${movielens_required_files[@]}"; do
    [[ -n "${seen_names[$required_file]:-}" ]] \
      || fail "MovieLens ml-32m full-official-release is missing: $required_file"
  done
fi

write_hdfs_manifest() {
  local load_status="$1" manifest="$output_dir/hdfs-manifest.json"
  local manifest_tmp="$output_dir/.hdfs-manifest.json.tmp.$$" index separator verified
  verified='false'
  [[ "$load_status" == 'SUCCESS' || "$load_status" == 'SKIPPED_IDENTICAL' ]] \
    && verified='true'
  {
    printf '{\n'
    printf '  "status": "%s",\n' "$load_status"
    printf '  "dataset": "%s",\n' "$(json_escape "$dataset")"
    printf '  "version": "%s",\n' "$(json_escape "$version")"
    printf '  "scope": "%s",\n' "$(json_escape "$scope")"
    printf '  "hdfs_target": "%s",\n' "$(json_escape "$hdfs_target")"
    printf '  "duplicate_policy": "%s",\n' "$duplicate_policy"
    printf '  "file_count": %d,\n' "${#resolved_files[@]}"
    printf '  "total_bytes": %d,\n' "$total_size"
    printf '  "files": [\n'
    for index in "${!resolved_files[@]}"; do
      separator=','
      [[ "$index" == "$((${#resolved_files[@]} - 1))" ]] && separator=''
      printf '    {"name":"%s","source_path":"%s","hdfs_path":"%s","bytes":%d,"sha256":"%s","checksum_verified":%s}%s\n' \
        "$(json_escape "$(basename "${resolved_files[$index]}")")" \
        "$(json_escape "${resolved_files[$index]}")" \
        "$(json_escape "$hdfs_target/$(basename "${resolved_files[$index]}")")" \
        "${file_sizes[$index]}" "${file_hashes[$index]}" "$verified" "$separator"
    done
    printf '  ]\n'
    printf '}\n'
  } >"$manifest_tmp" || fail 'cannot write HDFS manifest'
  mv -f -- "$manifest_tmp" "$manifest" || fail 'cannot publish HDFS manifest'
}

if [[ "$command_name" == 'manifest' ]]; then
  write_hdfs_manifest 'PREPARED_NOT_UPLOADED'
  printf 'Prepared manifest for %d files (%d bytes); HDFS was not contacted.\n' \
    "${#resolved_files[@]}" "$total_size" | tee "$output_dir/hdfs-summary.txt"
  exit 0
fi

require_command docker
docker inspect "$HDFS_CONTAINER" >/dev/null 2>&1 \
  || fail "$HDFS_CONTAINER is not running on this host"
safemode="$(docker exec "$HDFS_CONTAINER" hdfs dfsadmin -safemode get)" \
  || fail 'cannot read HDFS safe mode'
[[ "$safemode" == *OFF* ]] || fail 'HDFS safe mode must be OFF'
report="$(docker exec "$HDFS_CONTAINER" hdfs dfsadmin -report)" \
  || fail 'cannot read HDFS cluster report'
[[ "$report" == *'Live datanodes (2)'* ]] || fail 'exactly two live HDFS DataNodes are required'
[[ "$report" == *'Under replicated blocks: 0'* ]] || fail 'HDFS has under-replicated blocks'
[[ "$report" == *'Blocks with corrupt replicas: 0'* ]] || fail 'HDFS has corrupt replicas'
[[ "$report" == *'Missing blocks: 0'* ]] || fail 'HDFS has missing blocks'

if [[ "$command_name" == 'validate' ]]; then
  write_hdfs_manifest 'VALIDATED_NOT_UPLOADED'
  printf 'Validated %d files (%d bytes); HDFS upload was not run.\n' \
    "${#resolved_files[@]}" "$total_size" | tee "$output_dir/hdfs-summary.txt"
  exit 0
fi

remote_matches_local() {
  local index remote_hash remote_size remote_file remote_count
  remote_count="$(docker exec "$HDFS_CONTAINER" hdfs dfs -count "$hdfs_target" \
    | awk '{print $2}')" || return 1
  [[ "$remote_count" == "${#resolved_files[@]}" ]] || return 1
  for index in "${!resolved_files[@]}"; do
    remote_file="$hdfs_target/$(basename "${resolved_files[$index]}")"
    docker exec "$HDFS_CONTAINER" hdfs dfs -test -f "$remote_file" || return 1
    remote_size="$(docker exec "$HDFS_CONTAINER" hdfs dfs -stat '%b' "$remote_file")" || return 1
    [[ "$remote_size" == "${file_sizes[$index]}" ]] || return 1
    remote_hash="$({ docker exec "$HDFS_CONTAINER" hdfs dfs -cat "$remote_file"; } | sha256sum | awk '{print $1}')" \
      || return 1
    [[ "$remote_hash" == "${file_hashes[$index]}" ]] || return 1
  done
}

target_exists='false'
if docker exec "$HDFS_CONTAINER" hdfs dfs -test -e "$hdfs_target"; then
  target_exists='true'
fi
if [[ "$target_exists" == 'true' && "$duplicate_policy" == 'fail' ]]; then
  fail "HDFS target already exists: $hdfs_target"
fi
if [[ "$target_exists" == 'true' && "$duplicate_policy" == 'skip-if-same' ]]; then
  remote_matches_local || fail 'HDFS target exists but does not match the approved local files'
  write_hdfs_manifest 'SKIPPED_IDENTICAL'
  printf 'HDFS target already matches all approved files; upload skipped.\n' \
    | tee "$output_dir/hdfs-summary.txt"
  exit 0
fi

run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
container_stage="/tmp/feelm-initial-load-$run_id"
hdfs_stage="${hdfs_target}.__staging_${run_id}"
hdfs_backup="${hdfs_target}.__backup_${run_id}"
stage_created='false'
backup_created='false'

cleanup() {
  docker exec "$HDFS_CONTAINER" rm -rf -- "$container_stage" >/dev/null 2>&1 || true
  if [[ "$stage_created" == 'true' ]]; then
    docker exec "$HDFS_CONTAINER" hdfs dfs -rm -r -f "$hdfs_stage" >/dev/null 2>&1 || true
  fi
  if [[ "$backup_created" == 'true' ]]; then
    docker exec "$HDFS_CONTAINER" hdfs dfs -mv "$hdfs_backup" "$hdfs_target" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

docker exec "$HDFS_CONTAINER" mkdir -p "$container_stage" || fail 'cannot create container staging directory'
docker exec "$HDFS_CONTAINER" hdfs dfs -mkdir -p "$hdfs_stage" || fail 'cannot create HDFS staging directory'
stage_created='true'

for index in "${!resolved_files[@]}"; do
  name="$(basename "${resolved_files[$index]}")"
  docker cp "${resolved_files[$index]}" "$HDFS_CONTAINER:$container_stage/$name" \
    || fail "cannot copy source into NameNode container: $name"
  docker exec "$HDFS_CONTAINER" hdfs dfs -put "$container_stage/$name" "$hdfs_stage/$name" \
    || fail "cannot upload source to HDFS staging: $name"
  remote_size="$(docker exec "$HDFS_CONTAINER" hdfs dfs -stat '%b' "$hdfs_stage/$name")" \
    || fail "cannot verify HDFS size: $name"
  [[ "$remote_size" == "${file_sizes[$index]}" ]] || fail "HDFS size mismatch: $name"
  remote_hash="$({ docker exec "$HDFS_CONTAINER" hdfs dfs -cat "$hdfs_stage/$name"; } \
    | sha256sum | awk '{print $1}')" || fail "cannot verify HDFS checksum: $name"
  [[ "$remote_hash" == "${file_hashes[$index]}" ]] || fail "HDFS checksum mismatch: $name"
done

if [[ "$target_exists" == 'true' ]]; then
  docker exec "$HDFS_CONTAINER" hdfs dfs -mv "$hdfs_target" "$hdfs_backup" \
    || fail 'cannot move existing HDFS target to backup'
  backup_created='true'
fi
docker exec "$HDFS_CONTAINER" hdfs dfs -mv "$hdfs_stage" "$hdfs_target" \
  || fail 'cannot publish verified HDFS staging directory'
stage_created='false'
if [[ "$backup_created" == 'true' ]]; then
  docker exec "$HDFS_CONTAINER" hdfs dfs -rm -r -f "$hdfs_backup" \
    || fail 'new HDFS target is published but old backup cleanup failed'
  backup_created='false'
fi

write_hdfs_manifest 'SUCCESS'

trap - EXIT
docker exec "$HDFS_CONTAINER" rm -rf -- "$container_stage" >/dev/null 2>&1 || true
printf 'HDFS upload complete: files=%d bytes=%d target=%s\n' \
  "${#resolved_files[@]}" "$total_size" "$hdfs_target" \
  | tee "$output_dir/hdfs-summary.txt"
