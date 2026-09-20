#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <runtime-image-digest> <code-image-tag> <source-revision> <dotenv-output>" >&2
  exit 2
fi

SPARK_RUNTIME_IMAGE=$1
SPARK_CODE_IMAGE=$2
SOURCE_REVISION=$3
OUTPUT_FILE=$4

if [[ ! "$SPARK_RUNTIME_IMAGE" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "SPARK_RUNTIME_IMAGE must be an immutable repository@sha256 digest" >&2
  exit 2
fi
if [[ ! "$SPARK_CODE_IMAGE" =~ ^[^[:space:]@]+:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "SPARK_CODE_IMAGE must be a repository with an explicit build tag" >&2
  exit 2
fi
if [[ ! "$SOURCE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "SOURCE_REVISION must be a 40-character lowercase Git SHA" >&2
  exit 2
fi
if [[ -z "$OUTPUT_FILE" || -L "$OUTPUT_FILE" ]]; then
  echo "dotenv output must be a non-symlink path" >&2
  exit 2
fi

docker build \
  --pull \
  --build-arg SPARK_RUNTIME_IMAGE="$SPARK_RUNTIME_IMAGE" \
  --build-arg SOURCE_REVISION="$SOURCE_REVISION" \
  --tag "$SPARK_CODE_IMAGE" \
  --file infra/spark/Dockerfile.code \
  .
docker push "$SPARK_CODE_IMAGE"

SPARK_CODE_REPOSITORY="${SPARK_CODE_IMAGE%:*}"
mapfile -t matching_digests < <(
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' \
    "$SPARK_CODE_IMAGE" \
    | awk -v prefix="${SPARK_CODE_REPOSITORY}@" 'index($0, prefix) == 1'
)
if [[ "${#matching_digests[@]}" -ne 1 ]]; then
  echo "pushed Spark code image did not resolve to one repository-matching digest" >&2
  exit 1
fi
SPARK_IMAGE_DIGEST="${matching_digests[0]}"
if [[ ! "$SPARK_IMAGE_DIGEST" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "pushed Spark code image digest is invalid" >&2
  exit 1
fi

output_directory=$(dirname -- "$OUTPUT_FILE")
mkdir -p -- "$output_directory"
temporary_output=$(mktemp "${OUTPUT_FILE}.XXXXXX")
trap 'rm -f -- "$temporary_output"' EXIT
printf 'SPARK_IMAGE_DIGEST=%s\n' "$SPARK_IMAGE_DIGEST" > "$temporary_output"
printf 'SPARK_RUNTIME_IMAGE=%s\n' "$SPARK_RUNTIME_IMAGE" >> "$temporary_output"
chmod 0600 "$temporary_output"
mv -f -- "$temporary_output" "$OUTPUT_FILE"
trap - EXIT

echo "Built Spark code image for revision $SOURCE_REVISION"
